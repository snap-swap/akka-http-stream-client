package com.snapswap.http.client

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern._
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout

import scala.concurrent.Future
import scala.util.{Failure, Success}


object HttpClient {
  def apply(connection: HttpConnection.Connection,
            bufferSize: Int,
            overflowStrategy: OverflowStrategy)
           (implicit system: ActorSystem,
            mat: Materializer): HttpClient =
    new HttpClient(connection, bufferSize, overflowStrategy)
}

class HttpClient(connection: HttpConnection.Connection,
                 bufferSize: Int,
                 overflowStrategy: OverflowStrategy)
                (implicit system: ActorSystem,
                 mat: Materializer) {

  import system.dispatcher

  private val clientActor: ActorRef = system.actorOf(HttpClientActor.props(connection, bufferSize, overflowStrategy))

  def send(request: HttpRequest)
          (implicit timeout: Timeout): Future[HttpResponse] =
    send(request, new RequestMeta {}).map { case (response, _) => response }

  def send(request: HttpRequest, meta: RequestMeta)
          (implicit timeout: Timeout): Future[(HttpResponse, RequestMeta)] =
    (clientActor ? (request -> meta)).mapTo[WrappedResponse].map(processResponse)

  private def processResponse(r: WrappedResponse): (HttpResponse, RequestMeta) = r match {
    case WrappedResponse(Success(response), meta) =>
      response -> meta
    case WrappedResponse(Failure(ex), _) =>
      throw ex
  }

}
