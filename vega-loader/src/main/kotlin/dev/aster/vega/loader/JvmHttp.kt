package dev.aster.vega.loader

import dev.aster.vega.runtime.load.HostResolver
import dev.aster.vega.runtime.load.HttpResponse
import dev.aster.vega.runtime.load.HttpTransport
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * The JVM half of HTTP loading: one GET, no redirects, no policy.
 *
 * Everything that decides *whether* a URL may be fetched lives in
 * [dev.aster.vega.runtime.load.HttpDataLoader] and is common Kotlin. This is the socket, and it is
 * deliberately the only thing that has to be written again for another platform.
 *
 * `instanceFollowRedirects` is off because the loader follows redirects itself, so that the
 * allowlist and the private-network rule apply to every hop rather than only the first.
 */
public object JvmHttpTransport : HttpTransport {
  override fun get(url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int): HttpResponse {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection)
    connection.instanceFollowRedirects = false
    connection.connectTimeout = connectTimeoutMillis
    connection.readTimeout = readTimeoutMillis
    connection.requestMethod = "GET"
    try {
      val status = connection.responseCode
      val headers =
        connection.headerFields.entries
          .filter { it.key != null }
          .associate { it.key to it.value.joinToString(",") }
      // An error status still has a body worth nothing to us, and reading it would waste the cap.
      val body =
        if (status in 200..299) connection.inputStream.use { it.readBytes().decodeToString() }
        else ""
      return HttpResponse(status, headers, body)
    } finally {
      connection.disconnect()
    }
  }
}

/**
 * Name resolution for the private-network rule. An unresolvable host yields nothing, which denies.
 */
public object JvmHostResolver : HostResolver {
  override fun resolve(host: String): List<String> =
    try {
      InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
    } catch (e: UnknownHostException) {
      emptyList()
    }
}
