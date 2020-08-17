package com.snapswap.http.client.model

import akka.NotUsed
import akka.http.scaladsl.model.HttpRequest

case class EnrichedRequest[M](request: HttpRequest, meta: M, id: RequestId)

object EnrichedRequest {
  def apply[M](request: HttpRequest, meta: M): EnrichedRequest[M] =
    EnrichedRequest(request, meta, RequestId.random())

  def apply(request: HttpRequest, requestId: RequestId): EnrichedRequest[Any] =
    EnrichedRequest(request, NotUsed, requestId)

  def apply(request: HttpRequest): EnrichedRequest[Any] =
    apply(request, RequestId.random())
}