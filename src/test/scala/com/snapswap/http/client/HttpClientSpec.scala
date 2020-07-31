package com.snapswap.http.client

import akka.NotUsed
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import com.snapswap.http.client.HttpConnection.Connection
import org.scalatest.{AsyncWordSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

class HttpClientSpec
  extends AsyncWordSpecLike
    with Matchers
    with ScalatestRouteTest {

  import HttpClientSpec._

  override def testConfigSource: String =
    """
      |akka {
      |   loglevel="ERROR"
      |   http.host-connection-pool { //possible hotfix for snap-swap/remote-kyc#494, will increase concurrency for requests so we can handle the peaks
      |      max-open-requests = 1024
      |      max-connections = 512
      |   }
      |}
      |""".stripMargin

  implicit val timeout: Timeout = Timeout(1.minute)

  val serverRoute: Route = get(path("ping" / Segment) { payload =>
    Directives.complete {
      val delay = 100 + Random.nextInt(900)
      akka.pattern.after(delay.millis, system.scheduler)(Future.successful(HttpResponse(StatusCodes.OK, entity = payload)))
    }
  })

  Http().bindAndHandle(serverRoute, host, port)

  "HttpClient" when {
    "not receiving answers" should {
      val connection: Connection[NotUsed] = HttpConnection.superPool()
      implicit val client: HttpClient[NotUsed] = HttpClient(connection, Int.MaxValue, OverflowStrategy.dropNew)

      //it's not possible to reproduce it with the test server, so we'll use real jumio
      val requests = for (i <- 1 to numberOfRequests) yield {
        val schema = if (i % 2 == 0) Some("https://") else None //jumio won't reply without schema
        val url = s"${schema.getOrElse("")}lon.netverify.com/api/v4/initiate"
        Post(url) -> schema
      }

      "proceed to process other requests in 'Future (one request)' mode" in {
        val result = Future.traverse(requests) { case (r, p) =>
          // println(r.uri)
          send(r, p).flatMap(processResponse(_))
        }

        result.map { responses =>
          val successful = responses.collect { case (Success(r), m) => m -> r }
          val exceptions = responses.collect { case (Failure(ex), m) => m -> ex }

          requests.length shouldBe numberOfRequests
          responses.length shouldBe requests.length
          successful.length shouldBe requests.count { case (_, scheme) => scheme.isDefined }
          exceptions.length shouldBe requests.count { case (_, scheme) => scheme.isEmpty }
        }
      }
      "proceed to process other requests in 'Stream' mode" in {
        val result = client.send(Source(requests.toList))
          .mapAsync(1)(processResponse(_)).runWith(Sink.seq)

        result.map { responses =>
          val successful = responses.collect { case (Success(r), m) => m -> r }
          val exceptions = responses.collect { case (Failure(ex), m) => m -> ex }

          requests.length shouldBe numberOfRequests
          responses.length shouldBe requests.length
          successful.length shouldBe requests.count { case (_, scheme) => scheme.isDefined }
          exceptions.length shouldBe requests.count { case (_, scheme) => scheme.isEmpty }
        }
      }
    }
    "superPool" should {
      val connection: Connection[NotUsed] = HttpConnection.superPool()
      implicit val client: HttpClient[NotUsed] = HttpClient(connection, Int.MaxValue, OverflowStrategy.dropNew)

      "be able to perform vast amount of requests" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield Get(s"http://$host:$port/ping/$payload") -> payload

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

        val result = client.send(Source.fromIterator(() => requests.iterator))
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
      implicit val client: HttpClient[HostConnectionPool] = HttpClient(connection, Int.MaxValue, OverflowStrategy.dropNew)

      "be able to perform vast amount of requests" in {
        val requests = for (i <- 1 to numberOfRequests; payload = s"single$i") yield Get(s"/ping/$payload") -> payload

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

        val result = client.send(Source.fromIterator(() => requests.iterator))
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
  val numberOfRequests = 2000

  def send[M](r: HttpRequest, m: M)
             (implicit client: HttpClient[_],
              ec: ExecutionContext): Future[(Try[HttpResponse], M)] = {
    client.send(r, m)(Timeout(10.seconds)).recover {
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
