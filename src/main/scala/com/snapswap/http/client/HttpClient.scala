package com.snapswap.http.client

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, RequestTimeoutException}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.stream._
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import akka.util.Timeout
import com.snapswap.http.client.ConnectionParams.{HttpConnectionParams, HttpsConnectionParams, SuperPool}
import com.snapswap.http.client.HttpClient.HttpClientError
import com.snapswap.http.client.model.{EnrichedRequest, EnrichedResponse, RequestId}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success, Try}


object HttpClient {
  def apply(connectionContext: Option[HttpsConnectionContext] = None,
            connectionParams: ConnectionParams = SuperPool,
            poolSettings: Option[ConnectionPoolSettings] = None,
            logger: Option[LoggingAdapter] = None,
            bufferSize: Int = 1000)
           (implicit system: ActorSystem,
            ec: ExecutionContext,
            mat: Materializer): HttpClient =
    new HttpClient(connectionContext = connectionContext.getOrElse(Http().defaultClientHttpsContext),
      connectionParams = connectionParams,
      poolSettings = poolSettings.getOrElse(ConnectionPoolSettings(system)),
      logger = logger,
      bufferSize = bufferSize
    )

  case class HttpClientError(message: String) extends Throwable {
    override def getMessage: String = message
  }

}

class HttpClient(connectionContext: HttpsConnectionContext,
                 connectionParams: ConnectionParams,
                 poolSettings: ConnectionPoolSettings,
                 logger: Option[LoggingAdapter],
                 bufferSize: Int)
                (implicit system: ActorSystem,
                 ec: ExecutionContext,
                 mat: Materializer) {

  private val baseRetryDelay: FiniteDuration = 50.millis //TODO: make it configurable

  private def retryDelay: FiniteDuration =
    FiniteDuration((baseRetryDelay.length * Random.nextFloat).toLong, baseRetryDelay.unit) + baseRetryDelay

  //Affects only our consumers which only push requests to the pool, let it be the same as for the pool
  private val consumingParallelism = poolSettings.pipeliningLimit * poolSettings.maxConnections

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
    val (inQueue, processingFlow) = Source.queue[RequestWithCompletion](bufferSize, OverflowStrategy.dropNew) //to indicate that buffer is full
      .preMaterialize()

    processingFlow
      .map(requestWithCompletion => requestWithCompletion.httpRequest -> requestWithCompletion)
      //TODO: no needless to do it right now but remember, we can use throttling here
      .via(pool)
      //parallelism here already restricted by pool parallelism, so no need to put specific value here
      .mapAsyncUnordered(Int.MaxValue) { case (response, requestWithCompletion) =>
        requestWithCompletion.completeWithResult(response).map { underlyingResult =>
          underlyingResult -> response match {
            case (Failure(underlyingEx), Success(resp)) =>
              log.error(underlyingEx, s"got http response ${resp.status} but request ${requestWithCompletion.id} itself has been already failed")
            case (Failure(underlyingEx), Failure(ex)) if !underlyingEx.equals(ex) =>
              log.error(ex, s"exception during processing request ${requestWithCompletion.id}, but request was failed with another error $underlyingEx")
            case (Failure(underlyingEx), Failure(_)) =>
              log.error(underlyingEx, s"request ${requestWithCompletion.id} was failed")
            case (Success(underlyingResp), Failure(ex)) =>
              log.error(ex, s"exception during processing request ${requestWithCompletion.id}, but request somehow is completed with ${underlyingResp.status}")
            case (Success(underlyingResp), Success(resp)) if !underlyingResp.equals(resp) =>
              log.error(s"request ${requestWithCompletion.id} completion result is differ from the response (${underlyingResp.status} -> ${resp.status})")
            case (Success(underlyingResp), Success(_)) =>
              log.debug(s"request ${requestWithCompletion.id} was completed with ${underlyingResp.status}")
          }
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

  def send(request: HttpRequest)
          (implicit requestTimeout: Timeout): Future[Try[HttpResponse]] =
    send(EnrichedRequest(request)).map(_.response)

  def send[M](request: HttpRequest, meta: M)
             (implicit requestTimeout: Timeout): Future[(Try[HttpResponse], M)] =
    send(EnrichedRequest(request, meta)).map {
      case EnrichedResponse(response, EnrichedRequest(_, meta, _, _), _) =>
        response -> meta
    }

  def send[M](request: EnrichedRequest[M])
             (implicit requestTimeout: Timeout): Future[EnrichedResponse[M]] =
    send(Source.single(request)).runWith(Sink.head)

  /**
   * Here we implement backpressure mechanism to be able to offer elements from the different sources.
   * Standard OverflowStrategy.backpressure with SourceQueue works only if we offer elements only from one place
   **/
  private def offerIn[M](req: EnrichedRequestWithTimeout[M]): Future[Future[EnrichedResponse[M]]] = {
    if (req.awaitingResponse.isCompleted)
      req.awaitingResponse.map {
        case EnrichedResponse(Success(resp), _, _) =>
          log.error(s"request ${req.id} somehow already completed with ${resp.status}, no need to send it to the pool")
        case EnrichedResponse(Failure(ex), _, _) =>
          log.error(ex, s"request ${req.id} already completed with failure, no need to send it to the pool")
      }.map(_ => req.awaitingResponse)
    else
      in.offer(req).flatMap {
        case QueueOfferResult.Enqueued =>
          log.debug(s"Request ${req.id} Enqueued")
          Future.successful(req.awaitingResponse)
        case QueueOfferResult.Dropped => //buffer is full, need to wait and offer again
          val delay = retryDelay
          log.warning(s"pool buffer size is $bufferSize and seems it's overflowed, will try to offer request ${req.id} after $delay")
          akka.pattern.after(delay, system.scheduler)(offerIn(req))
        case result =>
          Future.failed(HttpClientError(s"expected Enqueued result for 'in' but got $result for request ${req.id}"))
      }
  }

  def send[M](requests: Source[EnrichedRequest[M], Any])
             (implicit requestTimeout: Timeout): Source[EnrichedResponse[M], Any] = {
    val (out, result) = Source.queue[Future[EnrichedResponse[M]]](1, OverflowStrategy.backpressure)
      .preMaterialize()

    requests
      .map { req =>
        log.debug(s"got new request ${req.id} ${req.request.method.value} ${req.request.uri.toString()}")
        //start ticking timeout for all requests
        EnrichedRequestWithTimeout(req, requestTimeout.duration)
      }
      .mapAsyncUnordered(consumingParallelism) { req =>
        //send requests to the pool - and then return incomplete future with response
        offerIn(req)
          .map { response => req.id -> response }
          .recover {
            case ex =>
              log.error(ex, s"can't send request ${req.id} to the pool, will fail it")
              req.id -> req.completeIfIncomplete(Failure(ex))
          }
      }
      .mapAsyncUnordered(1) { case (id, resp) => //parallelism should be always 1 here to make backpressure strategy work
        //send back response (may be incomplete) immediately
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

        /**
         * wait for the response readiness and then emit it
         * Note that all elements in 'result' are from 'out',
         * and they will be offered to 'out' only after they will be enqueued by 'in',
         * so no need to restrict concurrency (parallelism) here
         **/
        .mapAsyncUnordered(Int.MaxValue)(awaitingResponse => awaitingResponse)
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
        response.map(_.discardEntityBytes().future()).getOrElse(Future.successful(())).recover {
          case ex =>
            log.error(ex, s"request ${rr.id} has been already completed but response arrived and we discarded entity bytes and something went wrong here!")
        }.flatMap(_ => countdownResponse.future)
      else
        countdownResponse.complete(Success(EnrichedResponse(response, rr))).future
    }

    def awaitingResponse: Future[EnrichedResponse[M]] = countdownResponse.future

    def completeWithResult(response: Try[HttpResponse]): Future[Try[HttpResponse]] =
      completeIfIncomplete(response).map {
        case EnrichedResponse(result, _, _) => result
      }
  }

}
