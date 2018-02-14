package com.snapswap.http.client

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

import scala.util.Try


private[client] object HttpClientActor {

  def props(connection: HttpConnection.Connection,
            bufferSize: Int,
            overflowStrategy: OverflowStrategy)
           (implicit mat: Materializer): Props =
    Props(new HttpClientActor(connection, bufferSize, overflowStrategy))
}


private[client] class HttpClientActor(connection: HttpConnection.Connection,
                                      bufferSize: Int,
                                      overflowStrategy: OverflowStrategy)
                                     (implicit mat: Materializer) extends Actor with ActorLogging {

  private def sink = Sink.foreach[(Try[HttpResponse], RequestMeta)] {
    case (response, meta) =>
      self ! WrappedResponse(response, meta)
  }

  private val requestProcessor: ActorRef =
    Source.actorRef[(HttpRequest, RequestMeta)](bufferSize, overflowStrategy)
      .via(connection)
      .to(sink)
      .run()

  override def receive: Receive = {
    case (r: HttpRequest, m: RequestMeta) =>
      log.debug(s"Request to ${r.method.value} ${r.uri} will be send via streaming client")
      requestProcessor ! r -> ProxyMeta(sender(), m)
    case WrappedResponse(response, ProxyMeta(pipeTo, meta)) =>
      pipeTo ! WrappedResponse(response, meta)
  }

  private case class ProxyMeta(pipeTo: ActorRef, meta: RequestMeta) extends RequestMeta

}
