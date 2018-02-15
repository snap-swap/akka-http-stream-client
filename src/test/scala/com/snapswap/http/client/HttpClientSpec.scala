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

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


class HttpClientSpec
  extends TestKit(ActorSystem("test-client", ConfigFactory.load()))
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
    "be able to perform vast amount of requests" in {
      val requests = Seq.fill[HttpRequest](numberOfRequests)(Get("/ping"))

      val result = Future.traverse(requests) { r =>
        client.send(r, "single").flatMap(processResponse(_))
      }

      result.map { responses =>
        val exceptions = responses.collect { case (Failure(ex), _) => ex }
        exceptions.length shouldBe 0

        val successful = responses.collect { case (Success(r), m) if r == "pong" && m == "single" => r }
        successful.length shouldBe numberOfRequests
      }
    }
    "in opposite to the Source.singe approach" in {
      val requests = Seq.fill[(HttpRequest, String)](numberOfRequests)(Get("/ping") -> "Source.singe")

      val result = Future.traverse(requests) { r =>
        Source.single(r)
          .via(connection)
          .runWith(Sink.head)
          .flatMap(processResponse(_))
      }

      result.map { responses =>
        val exceptions = responses.collect { case (Failure(ex: akka.stream.BufferOverflowException), _) => ex }
        exceptions.length should be > 0

        val successful = responses.collect { case (Success(r), m) if r == "pong" && m == "Source.singe" => r }
        successful.length should be < numberOfRequests
      }
    }
    "be able to return result as a stream" in {
      val requests = Seq.fill[(HttpRequest, String)](numberOfRequests)(Get("/ping") -> "streaming")

      val result = client.send(Source.fromIterator(() => requests.toIterator))
        .mapAsync(1)(processResponse(_)).runWith(Sink.seq)

      result.map { responses =>
        val exceptions = responses.collect { case (Failure(ex), _) => ex }
        exceptions.length shouldBe 0

        val successful = responses.collect { case (Success(r), m) if r == "pong" && m == "streaming" => r }
        successful.length shouldBe numberOfRequests
      }
    }
  }

}


object HttpClientSpec {
  val host = "0.0.0.0"
  val port = 8000
  val responseDelay: FiniteDuration = 2.millis
  val numberOfRequests = 1000

  def processResponse[M](r: (Try[HttpResponse], M))
                        (implicit mat: Materializer,
                         ec: ExecutionContext): Future[(Try[String], M)] = r match {
    case (Success(response), meta) =>
      Unmarshal(response).to[String].map(Try(_) -> meta)
    case (Failure(ex), meta) =>
      Future.successful(Try(throw ex) -> meta)
  }
}
