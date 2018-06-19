package com.snapswap.http.client

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.testkit.TestKit
import akka.util.Timeout
import com.snapswap.http.client.HttpConnection.Connection
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
  implicit val timeout: Timeout = Timeout(1.minute)

  val serverRoute: Route = get(path("ping" / Segment) { payload =>
    Directives.complete(akka.pattern.after[HttpResponse](responseDelay, system.scheduler) {
      Future.successful(HttpResponse(StatusCodes.OK, entity = payload))
    })
  })

  Http().bindAndHandle(serverRoute, host, port)

  "HttpClient" when {
    "superPool" should {
      val connection: Connection[NotUsed] = HttpConnection.superPool()
      val client: HttpClient[NotUsed] = HttpClient(connection, Int.MaxValue, OverflowStrategy.dropNew)

      "be able to perform vast amount of requests" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield Get(s"http://$host:$port/ping/$payload") -> payload

        val result = Future.traverse(requests) { case (r, p) =>
          client.send(r, p).flatMap(processResponse(_))
        }

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), _) => ex }
          exceptions.length shouldBe 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length shouldBe numberOfRequests
        }
      }
      "in opposite to the Source.singe approach" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"Source.singe$i") yield Get(s"http://$host:$port/ping/$payload") -> payload

        val result = Future.traverse(requests) { pair =>
          Source.single(pair)
            .via(connection)
            .runWith(Sink.head)
            .flatMap(processResponse(_))
        }

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex: akka.stream.BufferOverflowException), _) => ex }
          exceptions.length should be > 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length should be < numberOfRequests
        }
      }
      "be able to return result as a stream" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"streaming$i") yield Get(s"http://$host:$port/ping/$payload") -> payload

        val result = client.send(Source.fromIterator(() => requests.toIterator))
          .mapAsync(1)(processResponse(_)).runWith(Sink.seq)

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), _) => ex }
          exceptions.length shouldBe 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length shouldBe numberOfRequests
        }
      }
    }
    "pool" should {
      val connection: HttpConnection.Connection[HostConnectionPool] = HttpConnection.httpPool(host, port)
      val client: HttpClient[HostConnectionPool] = HttpClient(connection, Int.MaxValue, OverflowStrategy.dropNew)

      "be able to perform vast amount of requests" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield Get(s"/ping/$payload") -> payload

        val result = Future.traverse(requests) { case (r, p) =>
          client.send(r, p).flatMap(processResponse(_))
        }

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), _) => ex }
          exceptions.length shouldBe 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length shouldBe numberOfRequests
        }
      }
      "in opposite to the Source.singe approach" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"Source.singe$i") yield Get(s"/ping/$payload") -> payload

        val result = Future.traverse(requests) { pair =>
          Source.single(pair)
            .via(connection)
            .runWith(Sink.head)
            .flatMap(processResponse(_))
        }

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex: akka.stream.BufferOverflowException), _) => ex }
          exceptions.length should be > 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length should be < numberOfRequests
        }
      }
      "be able to return result as a stream" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"streaming$i") yield Get(s"/ping/$payload") -> payload

        val result = client.send(Source.fromIterator(() => requests.toIterator))
          .mapAsync(1)(processResponse(_)).runWith(Sink.seq)

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), _) => ex }
          exceptions.length shouldBe 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length shouldBe numberOfRequests
        }
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
