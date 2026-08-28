package dev.aster.vega.loader

import dev.aster.vega.runtime.load.HostResolver
import dev.aster.vega.runtime.load.HttpResponse
import dev.aster.vega.runtime.load.HttpTransport
import dev.aster.vega.runtime.load.LoadDeniedException
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
  override fun get(
    url: String,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    maxResponseBytes: Long,
  ): HttpResponse {
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
        if (status in 200..299) {
          connection.inputStream.use { stream ->
            // **Bounded as it reads.** This was `readBytes()`, which materializes the whole
            // response before anything checks its size — so the cap the loader documents as
            // stopping a hostile server "streaming unbounded data into memory" was applied to
            // memory that had already been filled.
            val charset = charsetOf(connection.contentType)
            if (maxResponseBytes < 0) {
              stream.readBytes().toString(charset)
            } else {
              val out = java.io.ByteArrayOutputStream()
              val buffer = ByteArray(8 * 1024)
              var total = 0L
              while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxResponseBytes) {
                  throw LoadDeniedException(
                    "Response from '$url' exceeds $maxResponseBytes bytes; raise " +
                      "maxResponseBytes to allow a larger payload"
                  )
                }
                out.write(buffer, 0, read)
              }
              out.toByteArray().toString(charset)
            }
          }
        } else {
          ""
        }
      return HttpResponse(status, headers, body)
    } finally {
      connection.disconnect()
    }
  }

  /**
   * The charset the response says it is in, defaulting to UTF-8.
   *
   * `decodeToString()` assumed UTF-8 whatever the header said, so a `text/csv; charset=ISO-8859-1`
   * file — which is what a spreadsheet exported on Windows still produces — came back with a
   * replacement character wherever an accent had been. Upstream's loader reads the header for the
   * same reason.
   */
  private fun charsetOf(contentType: String?): java.nio.charset.Charset {
    val declared =
      contentType
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"', ' ') ?: return Charsets.UTF_8
    return try {
      java.nio.charset.Charset.forName(declared)
    } catch (unsupported: Exception) {
      Charsets.UTF_8
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
