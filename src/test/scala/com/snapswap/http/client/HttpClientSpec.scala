package com.snapswap.http.client

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.snapswap.http.client.ConnectionParams.{HttpConnectionParams, SuperPool}
import com.snapswap.http.client.model.{EnrichedRequest, EnrichedResponse}
import org.scalatest.{AsyncWordSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

class HttpClientSpec
  extends AsyncWordSpecLike
    with Matchers
    with ScalatestRouteTest {

  import HttpClientSpec._

  val serverRoute: Route = get(path("ping" / Segment) { payload =>
    Directives.complete {
      val delay = 100 + Random.nextInt(200)
      akka.pattern.after(delay.millis, system.scheduler)(Future.successful(HttpResponse(StatusCodes.OK, entity = payload)))
    }
  })

  Http().bindAndHandle(serverRoute, host, port)

  "HttpClient" when {
    "failure occurred in the connection pool flow (requests processing stream failed)" should {
      implicit val client: HttpClient = HttpClient(
        connectionParams = SuperPool,
        requestTimeout = 3.seconds //decrease it as much as possible just not to wait tests completion too long
      )
      val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield {
        val uri = Uri(s"http://$host:$port/ping/$payload")
        if (i % 2 == 0)
          Get(uri.copy(authority = Authority.Empty)) -> s"failed_$payload" //with empty authority pool flow will be failed with IllegalUriException
        else
          Get(uri) -> s"succeed_$payload"
      }

      "recover and proceed with other requests in 'Future (one request)' mode" in {
        val result = Future.traverse(requests) { case (r, p) =>
          send(r, p).flatMap(processResponse(_))
        }

        result.map { responses =>
          val successful = responses.collect { case (Success(r), m) => m -> r }
          val exceptions = responses.collect { case (Failure(ex), m) => m -> ex }

          requests.length shouldBe numberOfRequests
          responses.length shouldBe requests.length
          successful.length shouldBe requests.count { case (_, payload) => payload.startsWith("failed_") }
          exceptions.length shouldBe requests.count { case (_, payload) => payload.startsWith("succeed_") }
        }
      }
      "recover and proceed with other requests in 'Stream' mode" in {
        val result = send(Source(requests.toList))
          .mapAsyncUnordered(Int.MaxValue)(processResponse(_)).runWith(Sink.seq)

        result.map { responses =>
          val successful = responses.collect { case (Success(r), m) => m -> r }
          val exceptions = responses.collect { case (Failure(ex), m) => m -> ex }

          requests.length shouldBe numberOfRequests
          responses.length shouldBe requests.length
          successful.length shouldBe requests.count { case (_, payload) => payload.startsWith("failed_") }
          exceptions.length shouldBe requests.count { case (_, payload) => payload.startsWith("succeed_") }
        }
      }
    }
    "using superPool" should {
      implicit val client: HttpClient = HttpClient(
        connectionParams = SuperPool
      )
      val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield Get(s"http://$host:$port/ping/$payload") -> payload

      "be able to perform vast amount of requests" in {
        val result = Future.traverse(requests) { case (r, p) =>
          send(r, p).flatMap(processResponse(_))
        }

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), m) => ex -> m }
          val successful = responses.collect { case (Success(r), m) if r == m => r }

          exceptions.length shouldBe 0
          successful.length shouldBe numberOfRequests
        }
      }
      "in opposite to the Source.singe approach" in { //indicates that requests outside the pool capacity will be failed if they are performing simultaneously
        val connection = Http().superPool[Any]()
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
        val result = send(Source(requests.toList))
          .mapAsyncUnordered(Int.MaxValue)(processResponse(_)).runWith(Sink.seq)

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), _) => ex }
          exceptions.length shouldBe 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length shouldBe numberOfRequests
        }
      }
    }
    "using pool" should {
      implicit val client: HttpClient = HttpClient(
        connectionParams = HttpConnectionParams(host, port)
      )
      val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield Get(s"/ping/$payload") -> payload

      "be able to perform vast amount of requests" in {
        val result = Future.traverse(requests) { case (r, p) =>
          send(r, p).flatMap(processResponse(_))
        }

        result.map { responses =>
          val exceptions = responses.collect { case (Failure(ex), _) => ex }
          exceptions.length shouldBe 0

          val successful = responses.collect { case (Success(r), m) if r == m => r }
          successful.length shouldBe numberOfRequests
        }
      }
      "be able to return result as a stream" in {
        val result = send(Source(requests.toList))
          .mapAsyncUnordered(Int.MaxValue)(processResponse(_)).runWith(Sink.seq)

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
  val numberOfRequests = 2000 //should be greater host-connection-pool.max-connections to test that we can process requests outside the pool capacity

  def send[M](r: Source[(HttpRequest, M), Any])
             (implicit client: HttpClient,
              ec: ExecutionContext): Source[(Try[HttpResponse], M), Any] =
    client.send(r.map { case (r, m) => EnrichedRequest(r, m) })
      .map { case EnrichedResponse(response, EnrichedRequest(_, meta, _)) => response -> meta }

  def send[M](r: HttpRequest, m: M)
             (implicit client: HttpClient,
              ec: ExecutionContext): Future[(Try[HttpResponse], M)] = {
    client.send(r, m).recover {
      case ex =>
        Failure(ex) -> m
    }
  }

  def processResponse[M](r: (Try[HttpResponse], M))
                        (implicit mat: Materializer,
                         ec: ExecutionContext): Future[(Try[String], M)] = r match {
    case (Success(response), meta) =>
      Unmarshal(response).to[String].map(Success(_) -> meta)
    case (Failure(ex), meta) =>
      Future.successful(Failure(ex) -> meta)
  }
}
