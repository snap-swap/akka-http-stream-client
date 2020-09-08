package com.snapswap.http.client.model

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}

import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}


object MTLSConnectionContext {
  def apply(pksc12CertificateData: InputStream, pksc12CertificatePassword: String): HttpsConnectionContext = {
    val ks: KeyStore = KeyStore.getInstance("PKCS12")
    ks.load(pksc12CertificateData, pksc12CertificatePassword.toCharArray)

    val sslCtx = SSLContext.getInstance("TLS")
    val km = KeyManagerFactory.getInstance("SunX509")
    km.init(ks, pksc12CertificatePassword.toCharArray)
    val tm = TrustManagerFactory.getInstance("SunX509")
    tm.init(null.asInstanceOf[KeyStore])
    sslCtx.init(km.getKeyManagers, tm.getTrustManagers, new SecureRandom())

    ConnectionContext.https(sslCtx)
  }
}
