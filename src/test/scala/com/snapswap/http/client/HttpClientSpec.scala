package com.snapswap.http.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{get, path}
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.{AsyncWordSpecLike, Matchers}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


class HttpClientSpec
  extends TestKit(ActorSystem("test-client", ConfigFactory.parseString(HttpClientSpec.config)))
    with AsyncWordSpecLike
    with Matchers {

  import HttpClientSpec._

  implicit val mat: Materializer = ActorMaterializer()
  implicit val timeout: Timeout = Timeout(30.minutes)

  val serverRoute: Route = get(path("ping") {
    Directives.complete(akka.pattern.after[HttpResponse](responseDelay, system.scheduler) {
      Future.successful(HttpResponse(StatusCodes.OK, entity = "pong"))
    })
  })

  Http().bindAndHandle(serverRoute, host, port)

  val connection: HttpConnection.Connection = HttpConnection.httpPool(host, port)
  val client: HttpClient = HttpClient(connection, Int.MaxValue, OverflowStrategy.dropNew)

  "HttpClient" should {
    "be able to perform a vast amount of requests" in {
      val requests = Seq.fill[HttpRequest](numberOfRequests)(Get("/ping"))

      val result = Future.traverse(requests) { r =>
        client.send(r).map(Right(_)).recover { case ex => Left(ex) }.flatMap(processResponse)
      }

      result.map { responses =>
        val exceptions = responses.collect { case Left(ex) => ex }
        exceptions.length shouldBe 0

        val successful = responses.collect { case Right(r) if r == "pong" => r }
        successful.length shouldBe numberOfRequests
      }
    }
    "in opposite to the Source.singe approach" in {
      val requests = Seq.fill[HttpRequest](numberOfRequests)(Get("/ping"))

      val result = Future.traverse(requests) { r =>
        Source.single(r -> new RequestMeta {})
          .via(connection)
          .runWith(Sink.head)
          .flatMap(processResponse)
      }

      result.map { responses =>
        val exceptions = responses.collect { case Left(ex: akka.stream.BufferOverflowException) => ex }
        exceptions.length should be > 0

        val successful = responses.collect { case Right(r) if r == "pong" => r }
        successful.length should be < numberOfRequests
      }
    }
  }

}


object HttpClientSpec {
  val host = "0.0.0.0"
  val port = 8000
  val responseDelay: FiniteDuration = 2.millis
  val numberOfRequests = 1000

  val config: String =
    """
      |akka{
      |  log-level = "DEBUG"
      |  http {
      |    server {
      |      max-connections = 2048
      |    }
      |    host-connection-pool {
      |      max-open-requests = 32
      |      max-connections = 16
      |    }
      |  }
      |}
    """.stripMargin

  def processResponse(r: (Try[HttpResponse], RequestMeta))
                     (implicit mat: Materializer,
                      ec: ExecutionContext): Future[Either[Throwable, String]] = r match {
    case (Success(response), _) =>
      processResponse(Right(response))
    case (Failure(ex), _) =>
      processResponse(Left(ex))
  }

  def processResponse(r: Either[Throwable, HttpResponse])
                     (implicit mat: Materializer,
                      ec: ExecutionContext): Future[Either[Throwable, String]] = r match {
    case Left(ex) =>
      Future.successful(Left(ex))
    case Right(response) if response.status != StatusCodes.OK =>
      Future.successful(Left(new RuntimeException(s"response was unsuccessful ${response.status}")))
    case Right(response) =>
      Unmarshal(response).to[String].map(Right(_))
  }
}
