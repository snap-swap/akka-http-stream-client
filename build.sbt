name := "akka-http-stream-client"

organization := "com.snapswap"

version := "0.1.4"

scalaVersion := "2.11.11"

scalacOptions := Seq(
  "-feature",
  "-unchecked",
  "-deprecation",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Xfuture",
  "-Ywarn-unused-import",
  "-encoding",
  "UTF-8"
)


libraryDependencies ++= {
  val akkaHttpV = "10.0.10"
  val akkaV = "2.4.19"
  val scalatestV = "3.0.1"
  val logbackV = "1.2.3"
  Seq(
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % Test,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV % Test,
    "org.scalatest" %% "scalatest" % scalatestV % Test,
    "ch.qos.logback" % "logback-classic" % logbackV % Test
  )
}

