import java.io.{ByteArrayInputStream, IOException}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{Cookie, HttpCookiePair, Location, Referer, `Set-Cookie`, `User-Agent`}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling.{Unmarshal, _}
import akka.stream.Supervision._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, OverflowStrategy, QueueOfferResult, Supervision}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document => jsDocument, Element => jsElement}
import spray.json._

import scala.collection.immutable.{Seq => imSeq}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

object main extends SprayJsonSupport with DefaultJsonProtocol with LazyLogging {

  val config = ConfigFactory.load()
  implicit val system = ActorSystem("root")
  private implicit val context = system.dispatcher

  private val userAgentIPhone6Plus = "Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) AppleWebKit/600.1.3 (KHTML, like Gecko) Version/8.0 Mobile/12A4345d Safari/600.1.4"
  private val baseDomain = "www.zerobin.net"
//  private val baseDomain = "www.medscape.com"
  private val baseWebUrl = "http://www.medscape.com"

  private val decider: Decider = {
    case ex =>
      ex.printStackTrace()
      Supervision.Stop // Passes error down to subscriber
  }

  private case class RequestContext(categoryId: Long, newsId: Long)

  private implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))(system)
  private val poolClientFlow = initialize()
  private val queue = Source.queue[(HttpRequest, (Any, Promise[(Try[HttpResponse], Any)]))](1000, OverflowStrategy.backpressure)
    .via(poolClientFlow)
    .toMat(Sink.foreach({
      case (triedResp, (value: Any, p: Promise[(Try[HttpResponse], Any)])) =>
        println(s"Response was received ${value.toString}")
        p.success(triedResp -> value)
      case _ =>
        throw new RuntimeException()
    }))(Keep.left)
    .run

  private def initialize() = {

    val defaultSettings = ConnectionPoolSettings(config)
    val newSettings = defaultSettings.
      withPipeliningLimit(16).
      withMaxRetries(0).
      withMaxConnections(4)

    val connectionSettings = newSettings.connectionSettings
      .withUserAgentHeader(Option(`User-Agent`(userAgentIPhone6Plus)))
      .withConnectingTimeout(FiniteDuration(1, TimeUnit.SECONDS))

    val finalSettings = newSettings.withConnectionSettings(connectionSettings)

    Http()(system).cachedHostConnectionPool[Any](baseDomain, 80, finalSettings)
  }

  def sendQueuedRequest[T](request: HttpRequest, param: T): Future[(Try[HttpResponse], T)] = {

    val promise = Promise[(Try[HttpResponse], Any)]
    queue.offer(request -> (param -> promise)).flatMap {
      case QueueOfferResult.Enqueued =>
        println(s"Request enqueued ${param.toString}")
        promise.future.map { case (resp, value) => resp -> value.asInstanceOf[T] }
      case v: QueueOfferResult.Failure =>
        Future.failed(v.cause)
      case v =>
        Future.failed(new RuntimeException(s"${v.toString} returned as QueueOfferResult"))
    }
  }

  def testCall2() = {

    val headers = imSeq(Referer("https://www.google.com/"))
    val newsId2 = 862562L

//    val url = getNewsPathPrint(newsId2)
    val url = "/tos.html"
    val request = HttpRequest(uri = url).withHeaders(headers)

    val sequence: Future[imSeq[(Try[HttpResponse], Long)]] = Future.sequence((1 to 10).map { index =>
      sendQueuedRequest(request, index.toLong)
    })

    val qwe = sequence.map { case responses =>

      println(s"Got ${responses.length} responses")

      Future.sequence(responses.map { case (tryResp, index: Long) =>

        println(s"Parsing items ID $index")
        val marsh = jsoupHtmlUnmarshaller(baseWebUrl + getNewsPathPrint(index))
        val rc = RequestContext(index, index)
        val qwe: Future[jsDocument] = parseResponse[jsDocument](Future.successful(tryResp -> rc))(marsh)
        qwe.map { newsHtml =>
          println(s"Got ${newsHtml.toString.length} characters in html for index $index")
        }
      })
    }

    qwe.flatMap(identity)
  }

  def jsoupHtmlUnmarshaller(reqUrl: String): FromEntityUnmarshaller[jsDocument] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`text/html`).mapWithCharset { (data, charset) ⇒
      try {
        Jsoup.parse(new ByteArrayInputStream(data.toArray), charset.value, reqUrl)
      } catch {
        case ex: Throwable => throw new RuntimeException(s"Cannot parse document with Jsoup [${ex.getMessage}}]")
      }
    }

  private def getNewsPathPrint(newsId: Long): String = s"/viewarticle/${newsId}_print"

  private def parseResponse[TR](response: Future[(Try[HttpResponse], RequestContext)], redirectCount: Int = 0)(implicit unmarshaller: FromEntityUnmarshaller[TR]): Future[TR] = {

    def handleRedirect(resp: HttpResponse, reqContext: RequestContext): Future[TR] = {
      resp.header[Location] match {
        case Some(value) =>
          if (redirectCount > 1)
            Future.failed(throw new RuntimeException(s"Possible redirect loop? Redirect count is $redirectCount. Location is ${value.uri.toString()}"))
          else {
            val newCookies = resp.headers.filter(_.isInstanceOf[`Set-Cookie`]).map { v =>
              val cookie = v.asInstanceOf[`Set-Cookie`].cookie
              HttpCookiePair.apply(cookie.name, cookie.value)
            }
            parseResponse(sendQueuedRequest(HttpRequest(uri = value.uri.toRelative).withHeaders(imSeq(Cookie(newCookies))), reqContext), redirectCount + 1)(unmarshaller)
          }

        case None =>
          Future.failed(new IOException(s"Got HTTP 302 response but Location header is missing"))
      }
    }

    response.flatMap {
      case (tryResp, reqContext) =>

        tryResp match {
          case Success(res) =>
            res.status match {
              case OK =>
                val fut = unmarshaller(res.entity).recoverWith {
                  case ex =>
                    Unmarshal(res.entity).to[String].flatMap { body =>
                      Future.failed(new IOException(s"Failed to unmarshal with ${ex.getMessage} and response body is\n $body"))
                    }
                }
                //              fut.map {case tr: TR =>
                //                println("Exhausting response bytes")
                //                res.entity.dataBytes.runWith(Sink.ignore)
                //                tr
                //              }
                fut
              case Found =>
                handleRedirect(res, reqContext)
              case MovedPermanently =>
                handleRedirect(res, reqContext)
              case _ =>
                Unmarshal(res.entity).to[String].flatMap { body =>
                  Future.failed(new IOException(s"The response status is ${res.status} and response body is $body"))
                }
            }
          case Failure(ex) =>
            Future.failed(ex)
        }
    }
  }
  def main(args: Array[String]): Unit = {

    testCall2().onComplete {
      case Success(res) =>
        println(res)

        system.terminate()
      case Failure(ex) =>
        ex.printStackTrace()

        system.terminate()
    }

    Await.ready(system.whenTerminated, Duration.Inf)
  }
}