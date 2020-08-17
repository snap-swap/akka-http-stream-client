package com.snapswap.http.client.model

import akka.http.scaladsl.model.HttpResponse

import scala.util.Try

case class EnrichedResponse[M](response: Try[HttpResponse], request: EnrichedRequest[M]) {
  def id: RequestId = request.id
}