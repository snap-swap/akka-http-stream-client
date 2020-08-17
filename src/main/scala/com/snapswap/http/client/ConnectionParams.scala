package com.snapswap.http.client

sealed trait ConnectionParams

object ConnectionParams {

  case class HttpConnectionParams(host: String, port: Int) extends ConnectionParams

  case class HttpsConnectionParams(host: String, port: Int) extends ConnectionParams

  case object SuperPool extends ConnectionParams
}
