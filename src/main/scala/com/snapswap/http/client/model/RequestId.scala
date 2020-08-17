package com.snapswap.http.client.model

import scala.util.Random


case class RequestId(value: String) {
  override def toString: String = value
}

object RequestId {
  def random(): RequestId = RequestId(Random.alphanumeric.take(16).mkString)
}