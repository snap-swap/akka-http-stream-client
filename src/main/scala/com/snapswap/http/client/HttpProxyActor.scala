package com.snapswap.http.client

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

import scala.util.{Failure, Success, Try}


private[client] object HttpProxyActor {

  def props[T](connection: HttpConnection.Connection[T],
            bufferSize: Int,
            overflowStrategy: OverflowStrategy)
           (implicit mat: Materializer): Props =
    Props(new HttpProxyActor(connection, bufferSize, overflowStrategy))

  case class SourceWrapper(source: Source[(HttpRequest, Any), Any])

}


private[client] class HttpProxyActor[T](connection: HttpConnection.Connection[T],
                                     bufferSize: Int,
                                     overflowStrategy: OverflowStrategy)
                                    (implicit mat: Materializer) extends Actor with ActorLogging {

  private def sink = Sink.foreach[(Try[HttpResponse], ProxyMeta)] {
    case (response, ProxyMeta(pipeTo, meta, request)) =>
      lazy val status = response match {
        case Success(_) =>
          "successfully processed"
        case Failure(ex) =>
          s"failed with exception ${ex.getClass.getSimpleName}"
      }
      log.debug(s"Request to ${request.method.value} ${request.uri} $status")
      pipeTo ! response -> meta
  }

  private val requestsProcessor: ActorRef =
    Source.actorRef[Source[(HttpRequest, ProxyMeta), Any]](bufferSize, overflowStrategy)
      .flatMapConcat { r => r }
      .via(connection)
      .collect {
        case (r, m: ProxyMeta) =>
          r -> m
        case (_, m) =>
          throw new RuntimeException(s"expected ProxyMeta but got ${m.getClass.getSimpleName}")
      }
      .to(sink)
      .run()

  override def receive: Receive = {
    case (HttpProxyActor.SourceWrapper(source), pipeTo: ActorRef) =>
      val rm = source.map { case (request, meta) =>
        log.debug(s"Request to ${request.method.value} ${request.uri} will be processed and returned in Stream")
        request -> ProxyMeta(pipeTo, meta, request)
      }
      requestsProcessor ! rm
    case (request: HttpRequest, meta) =>
      log.debug(s"Request to ${request.method.value} ${request.uri} will be processed and returned in Future")
      requestsProcessor ! Source.single(request -> ProxyMeta(sender(), meta, request))
  }

  private case class ProxyMeta(pipeTo: ActorRef, meta: Any, request: HttpRequest)

}
