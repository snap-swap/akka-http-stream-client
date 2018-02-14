package com.snapswap.http.client

import akka.http.scaladsl.model.HttpResponse

import scala.util.Try


private[client] case class WrappedResponse(response: Try[HttpResponse], meta: RequestMeta)