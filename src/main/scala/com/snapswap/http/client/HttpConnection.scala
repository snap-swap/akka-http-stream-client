package com.snapswap.http.client

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

import scala.util.Try

object HttpConnection {

  type Connection = Flow[(HttpRequest, Any), (Try[HttpResponse], Any), HostConnectionPool]

  def defaultConnectionPoolSettings(implicit system: ActorSystem): ConnectionPoolSettings =
    ConnectionPoolSettings(system)

  def systemLogging(implicit system: ActorSystem): LoggingAdapter =
    system.log

  def defaultClientHttpsContext(implicit system: ActorSystem): HttpsConnectionContext =
    Http().defaultClientHttpsContext


  def httpPool(host: String, port: Int)
              (implicit system: ActorSystem, mat: Materializer): Connection =
    Http().cachedHostConnectionPool[Any](host, port)

  def httpPool(host: String, port: Int,
               settings: ConnectionPoolSettings,
               log: LoggingAdapter)
              (implicit system: ActorSystem, mat: Materializer): Connection =
    Http().cachedHostConnectionPool[Any](host, port, settings, log)


  def httpsPool(host: String, port: Int)
               (implicit system: ActorSystem, mat: Materializer): Connection =
    Http().cachedHostConnectionPoolHttps[Any](host, port)

  def httpsPool(host: String, port: Int,
                connectionContext: HttpsConnectionContext,
                settings: ConnectionPoolSettings,
                log: LoggingAdapter)
               (implicit system: ActorSystem, mat: Materializer): Connection =
    Http().cachedHostConnectionPoolHttps[Any](host, port, connectionContext, settings, log)
}
