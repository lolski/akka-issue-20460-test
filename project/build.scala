import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import com.typesafe.sbt.packager.docker.DockerPlugin
import net.virtualvoid.sbt.graph.Plugin.graphSettings
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyKeys._
import sbtassembly.{AssemblyPlugin, MergeStrategy, PathList}
import spray.revolver.RevolverPlugin.Revolver

object build extends Build {

  lazy val root = Project(
    id = "akka-flow-bug",
    base = file("."),
    settings = Defaults.coreDefaultSettings ++ graphSettings ++ Revolver.settings ++ Seq(
      version := "1.0",
      scalaVersion := "2.11.7",
      libraryDependencies ++= {
        val akkaV       = "2.4.4"
        val akkaStreamV = "1.0"
        val scalaTestV  = "2.2.5"
        Seq(
          "org.scala-lang" % "scala-reflect" % "2.11.7",
          "com.typesafe.akka" %% "akka-actor" % akkaV,
          "com.typesafe.akka" %% "akka-stream" % akkaV withSources(),
          "com.typesafe.akka" %% "akka-http-core" % akkaV withSources(),
          "com.typesafe.akka" %% "akka-http-experimental" % akkaV withSources(),
          "com.typesafe.akka" %% "akka-http-spray-json-experimental" % akkaV withSources(),
          "org.reflections"   % "reflections" % "0.9.10",
          "org.jsoup" % "jsoup" % "1.8.3",
          "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
          "ch.qos.logback" % "logback-classic" % "1.1.3",
          "org.scalatest"     %% "scalatest"                          % scalaTestV % "test"
        )
      },
      scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-language", "implicitConversions"),
      crossPaths := false,
      mainClass in Compile := Some("co.kernelnetworks.medstream.server.HttpService"),
      assemblyMergeStrategy in assembly := {
        case PathList("application.conf") => MergeStrategy.concat
        case x => MergeStrategy.defaultMergeStrategy(x)
      }
    )
  ).enablePlugins(AssemblyPlugin).enablePlugins(JavaAppPackaging).enablePlugins(DockerPlugin)
}
