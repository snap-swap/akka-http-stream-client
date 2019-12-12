name := "akka-http-stream-client"

organization := "com.snapswap"

version := "0.1.5"

val scalaV11 = "2.11.11"
val scalaV13 = "2.13.1"

scalaVersion := scalaV11

lazy val supportedScalaVersions = Seq(scalaV11, scalaV13)

crossScalaVersions := supportedScalaVersions

scalacOptions := Seq(
  "-feature",
  "-unchecked",
  "-deprecation",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
//  "-Xfatal-warnings",
  "-Xlint",
//  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
//  "-Xfuture",
//  "-Ywarn-unused-import",
  "-encoding",
  "UTF-8"
)


libraryDependencies ++= {
  val akkaHttpV = "10.1.10"
  val akkaV = "2.5.26"
  val scalatestV = "3.0.8"
  Seq(
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % Test,
    "org.scalatest" %% "scalatest" % scalatestV % Test
  )
}

