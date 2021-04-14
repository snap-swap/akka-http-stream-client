package com.snapswap.http.client.model

import java.time.Instant

import akka.http.scaladsl.model.HttpResponse

import scala.concurrent.duration._
import scala.util.Try

case class EnrichedResponse[M](response: Try[HttpResponse], request: EnrichedRequest[M], timestamp: Instant) {
  def duration: FiniteDuration = (timestamp.toEpochMilli - request.timestamp.toEpochMilli).millis
}

object EnrichedResponse {
  def apply[M](response: Try[HttpResponse], request: EnrichedRequest[M]): EnrichedResponse[M] =
    EnrichedResponse(response, request, Instant.now())
}