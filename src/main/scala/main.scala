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
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

object main extends SprayJsonSupport with DefaultJsonProtocol with LazyLogging {

  val config = ConfigFactory.load()
  implicit val system = ActorSystem("root")
  private implicit val context = system.dispatcher

  private val userAgentIPhone6Plus = "Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) AppleWebKit/600.1.3 (KHTML, like Gecko) Version/8.0 Mobile/12A4345d Safari/600.1.4"
  private val baseDomain = "www.zerobin.net"
  private val baseWebUrl = "http://www.zerobin.net"

  private val decider: Decider = {
    case ex =>
      ex.printStackTrace()
      Supervision.Stop // Passes error down to subscriber
  }

  private case class RequestContext(categoryId: Long, newsId: Long)

  private implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))(system)
  private val poolClientFlow = initialize()

  private val queue = Source.queue[(HttpRequest, (Any, Promise[(Try[HttpResponse], Any)]))](1000, OverflowStrategy.backpressure)
  private val sink = Sink.foreach[(Try[HttpResponse], Any)] {
    case (triedResp, (index: Long, p: Promise[(Try[HttpResponse], Any)])) =>
      println(s"Response was received $index")
      p.success(triedResp -> index)

      val marsh = jsoupHtmlUnmarshaller(baseWebUrl + getNewsPathPrint(index))
      val rc = RequestContext(index, index)
      val qwe: Future[jsDocument] = parseResponse[jsDocument](Future.successful(triedResp -> rc))(marsh)
      qwe.map { newsHtml =>
        println(s"Got ${newsHtml.toString.length} characters in html for index $index")
      } recover { case t: Throwable =>
        println(s"Error parsing ${t.getMessage} for index $index")
      }

    case (triedResp, (value @ RequestContext(catId, newsId), p: Promise[(Try[HttpResponse], Any)])) =>
      println(s"Response was received ${value.toString}")

      p.success(triedResp -> value)

    case _ =>
      throw new RuntimeException()
  }

  private val pipeline = queue.via(poolClientFlow).toMat(sink)(Keep.left).run

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
    pipeline.offer(request -> (param -> promise)) flatMap {
      case QueueOfferResult.Enqueued =>
        println(s"Request enqueued ${param.toString}")
        promise.future.map { case (resp, value) => resp -> value.asInstanceOf[T] }
      case v: QueueOfferResult.Failure =>
        Future.failed(v.cause)
      case v =>
        Future.failed(new RuntimeException(s"${v.toString} returned as QueueOfferResult"))
    }
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
    println("parse response ")
    response.flatMap {
      case (tryResp, reqContext) =>
        tryResp match {
          case Success(res) =>
            val en = Await.result(res.entity.toStrict(10 seconds), 10 seconds)

            res.status match {
              case OK =>
                val x = unmarshaller(en).recoverWith {
                  case ex =>
                    Unmarshal(en).to[String].flatMap { body =>
                      Future.failed(new IOException(s"Failed to unmarshal with ${ex.getMessage} and response body is\n $body"))
                    }
                }
                x
              case Found =>
                handleRedirect(res, reqContext)
              case MovedPermanently =>
                handleRedirect(res, reqContext)
              case _ =>
                Unmarshal(en).to[String].flatMap { body =>
                  Future.failed(new IOException(s"The response status is ${res.status} and response body is $body"))
                }
            }
          case Failure(ex) =>
            Future.failed(ex)
        }
    }
  }
  def main(args: Array[String]): Unit = {
    val headers = imSeq(Referer("https://www.google.com/"))
    val url = "/tos.html"
    val request = HttpRequest(uri = url).withHeaders(headers)

    val requests = (1 to 10) map { index => sendQueuedRequest(request, index.toLong) }
    val join = Future.sequence(requests)
    join onSuccess { case _ => pipeline.complete() }
    join onFailure { case t => pipeline.fail(t) }

    pipeline.watchCompletion().onComplete {
      case Success(res) =>
        println("done (successful)" + res)
        system.terminate()
      case Failure(ex) =>
        ex.printStackTrace()
        system.terminate()
    }

    Await.ready(system.whenTerminated, Duration.Inf)
  }
}