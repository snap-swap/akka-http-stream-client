package com.snapswap.http.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

import scala.util.Try

object HttpConnection {

  type Connection = Flow[(HttpRequest, Any), (Try[HttpResponse], Any), HostConnectionPool]

  def httpPool(host: String, port: Int)
              (implicit system: ActorSystem, mat: Materializer): Connection =
    Http().cachedHostConnectionPool[Any](host, port)

  def httpsPool(host: String, port: Int)
               (implicit system: ActorSystem, mat: Materializer): Connection =
    Http().cachedHostConnectionPoolHttps[Any](host, port)
}
