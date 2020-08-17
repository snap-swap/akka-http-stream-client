package com.snapswap.http.client

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, RequestTimeoutException}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.stream._
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import com.snapswap.http.client.ConnectionParams.{HttpConnectionParams, HttpsConnectionParams, SuperPool}
import com.snapswap.http.client.HttpClient.HttpClientError
import com.snapswap.http.client.model.{EnrichedRequest, EnrichedResponse, RequestId}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}


object HttpClient {
  def apply(connectionContext: Option[HttpsConnectionContext] = None,
            connectionParams: ConnectionParams = SuperPool,
            poolSettings: Option[ConnectionPoolSettings] = None,
            logger: Option[LoggingAdapter] = None,
            bufferSize: Int = Int.MaxValue,
            requestTimeout: FiniteDuration = 20.seconds)
           (implicit system: ActorSystem,
            ec: ExecutionContext,
            mat: Materializer): HttpClient =
    new HttpClient(connectionContext = connectionContext.getOrElse(Http().defaultClientHttpsContext),
      connectionParams = connectionParams,
      poolSettings = poolSettings.getOrElse(ConnectionPoolSettings(system)),
      logger = logger,
      bufferSize = bufferSize,
      requestTimeout = requestTimeout
    )

  case class HttpClientError(message: String) extends Throwable {
    override def getMessage: String = message
  }

}

class HttpClient(connectionContext: HttpsConnectionContext,
                 connectionParams: ConnectionParams,
                 poolSettings: ConnectionPoolSettings,
                 logger: Option[LoggingAdapter],
                 bufferSize: Int,
                 requestTimeout: FiniteDuration)
                (implicit system: ActorSystem,
                 ec: ExecutionContext,
                 mat: Materializer) {
  /**
   * Affects only our consumers which only push requests to the pool and then fan out incomplete requests.
   * Actually pool has it own parallelism which can be configured in the akka.http.host-connection-pool section
   * and calculated as poolSettings.pipeliningLimit * poolSettings.maxConnections
   **/
  private val consumingParallelism = Int.MaxValue

  /**
   * Affects real responses processing:
   *  - complete incomplete request by real response from pool
   *  - emit complete request to the outgoing stream
   **/
  private val resultsProcessingParallelism = Int.MaxValue

  private val log = logger.getOrElse(Logging.getLogger(system, this.getClass.getSimpleName))

  private val pool = {
    connectionParams match {
      case HttpConnectionParams(host, port) =>
        Http().cachedHostConnectionPool[RequestWithCompletion](host, port, poolSettings, log)
      case HttpsConnectionParams(host, port) =>
        Http().cachedHostConnectionPoolHttps[RequestWithCompletion](host, port, connectionContext, poolSettings, log)
      case SuperPool =>
        Http().superPool[RequestWithCompletion](connectionContext, poolSettings, log)
    }
  }

  private val in: SourceQueueWithComplete[RequestWithCompletion] = {
    val (inQueue, processingFlow) = Source.queue[RequestWithCompletion](bufferSize, OverflowStrategy.backpressure)
      .preMaterialize()

    processingFlow
      .map(requestWithCompletion => requestWithCompletion.httpRequest -> requestWithCompletion)
      //TODO: no needless to do it right now but remember, we can use throttling here
      .via(pool)
      .mapAsyncUnordered(resultsProcessingParallelism) { case (response, requestWithCompletion) =>
        requestWithCompletion.completeWithResult(response).map {
          case Failure(ex) =>
            log.error(ex, s"exception during processing request ${requestWithCompletion.id}")
          case Success(resp) =>
            log.debug(s"request ${requestWithCompletion.id} was completed with ${resp.status}")
        }
      }
      .to(Sink.ignore)
      .withAttributes(ActorAttributes.supervisionStrategy({
        ex =>
          log.error(ex, "error in the processing flow, resume")
          Supervision.Resume
      }))
      .run()

    inQueue
  }

  def send(request: HttpRequest): Future[Try[HttpResponse]] =
    send(EnrichedRequest(request)).map(_.response)

  def send[M](request: HttpRequest, meta: M): Future[(Try[HttpResponse], M)] =
    send(EnrichedRequest(request, meta)).map {
      case EnrichedResponse(response, EnrichedRequest(_, meta, _)) =>
        response -> meta
    }

  def send[M](request: EnrichedRequest[M]): Future[EnrichedResponse[M]] =
    send(Source.single(request)).runWith(Sink.head)

  def send[M](requests: Source[EnrichedRequest[M], Any]): Source[EnrichedResponse[M], Any] = {
    val (out, result) = Source.queue[Future[EnrichedResponse[M]]](bufferSize, OverflowStrategy.backpressure)
      .preMaterialize()

    requests
      .map { req =>
        log.debug(s"got new request ${req.id} ${req.request.method.value} ${req.request.uri.toString()}")
        //start ticking timeout for all requests
        EnrichedRequestWithTimeout(req, requestTimeout)
      }
      .mapAsyncUnordered(consumingParallelism) { req =>
        //send requests to the pool - one by one and then return incomplete future with response
        in.offer(req).map {
          case QueueOfferResult.Enqueued =>
            req.id -> req.awaitingResponse
          case result =>
            throw HttpClientError(s"expected Enqueued result for 'in' but got $result for request ${req.id}")
        }.recover {
          case ex =>
            log.error(ex, s"can't send request ${req.id} to the pool, will fail it")
            req.id -> req.completeIfIncomplete(Failure(ex))
        }
      }
      .mapAsyncUnordered(resultsProcessingParallelism) { case (id, resp) =>
        //send back to the client response (may be incomplete) immediately
        out.offer(resp).map {
          case QueueOfferResult.Enqueued =>
          case result =>
            throw HttpClientError(s"expected Enqueued result for 'out' but got $result for request $id")
        }.recover {
          case ex =>
            log.error(ex, s"can't send back awaiting response $id")
        }
      }
      .zipWith(result
        //wait for the response readiness and then emit it
        .mapAsyncUnordered(resultsProcessingParallelism)(awaitingResponse => awaitingResponse)
      ) { case (_, response) => response }
  }

  private trait RequestWithCompletion {
    def httpRequest: HttpRequest

    def id: RequestId

    def completeWithResult(response: Try[HttpResponse]): Future[Try[HttpResponse]]
  }

  private case class EnrichedRequestWithTimeout[M](rr: EnrichedRequest[M], timeout: FiniteDuration) extends RequestWithCompletion {
    private val countdownResponse = Promise.apply[EnrichedResponse[M]]()
    private val scheduledFailure = system.scheduler.scheduleOnce(timeout) {
      countdownResponse.complete(
        Success(EnrichedResponse(
          Failure(RequestTimeoutException(httpRequest, s"request wasn't completed in $timeout")),
          rr
        ))
      )
    }

    def id: RequestId = rr.id

    def httpRequest: HttpRequest = rr.request

    def completeIfIncomplete(response: Try[HttpResponse]): Future[EnrichedResponse[M]] = {
      scheduledFailure.cancel()
      if (countdownResponse.isCompleted)
        countdownResponse.future
      else
        countdownResponse.complete(Success(EnrichedResponse(response, rr))).future
    }

    def awaitingResponse: Future[EnrichedResponse[M]] = countdownResponse.future

    def completeWithResult(response: Try[HttpResponse]): Future[Try[HttpResponse]] =
      completeIfIncomplete(response).map {
        case EnrichedResponse(result, _) => result
      }
  }

}
