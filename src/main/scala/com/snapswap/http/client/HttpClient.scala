package com.snapswap.http.client

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern._
import akka.stream.scaladsl.Source
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout

import scala.concurrent.Future
import scala.util.Try


object HttpClient {
  def apply[T](connection: HttpConnection.Connection[T],
            bufferSize: Int,
            overflowStrategy: OverflowStrategy)
           (implicit system: ActorSystem,
            mat: Materializer): HttpClient[T] =
    new HttpClient(connection, bufferSize, overflowStrategy)
}

class HttpClient[T](connection: HttpConnection.Connection[T],
                 bufferSize: Int,
                 overflowStrategy: OverflowStrategy)
                (implicit system: ActorSystem,
                 mat: Materializer) {

  import system.dispatcher

  private val proxyActor: ActorRef = system.actorOf(
    HttpProxyActor.props(connection, bufferSize, overflowStrategy),
    s"http-client-proxy-${this.hashCode()}"
  )

  def send[M](requests: Source[(HttpRequest, M), Any]): Source[(Try[HttpResponse], M), Any] = {
    Source.actorRef[(Try[HttpResponse], M)](bufferSize, overflowStrategy)
      .mapMaterializedValue { outActor =>
        proxyActor ! HttpProxyActor.SourceWrapper(requests) -> outActor
      }
      .zipWith(requests) { case (out, _) => out }
  }

  def send(request: HttpRequest)
          (implicit timeout: Timeout): Future[Try[HttpResponse]] =
    send(request, ()).map { case (response, _) => response }

  def send[M](request: HttpRequest, meta: M)
             (implicit timeout: Timeout): Future[(Try[HttpResponse], M)] =
    (proxyActor ? (request -> meta)).mapTo[(Try[HttpResponse], M)]
}
