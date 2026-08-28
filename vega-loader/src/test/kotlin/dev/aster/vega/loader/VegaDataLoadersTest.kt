package dev.aster.vega.loader

import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.FallbackDataLoader
import dev.aster.vega.runtime.load.HttpResponse
import dev.aster.vega.runtime.load.HttpTransport
import dev.aster.vega.runtime.load.LoadDeniedException
import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The composition every published Vega specification needs: `data/barley.json` served from disk if
 * it is there and from a base URL if it is not.
 *
 * Tested with a recording transport rather than a socket, for the same reason the policy tests are:
 * the interesting behaviour is *which* loader was asked and whether the other one was, and a real
 * fetch would prove neither while making the suite depend on a network.
 */
class VegaDataLoadersTest {

  /** Serves a fixed body and records every URL it was asked for. */
  private class Recorder(private val bodies: Map<String, String> = emptyMap()) : HttpTransport {
    val requested = mutableListOf<String>()

    /**
     * The byte budget the loader hands down, so a transport can stop reading rather than truncate.
     */
    var budget: Long = -1

    override fun get(
      url: String,
      connectTimeoutMillis: Int,
      readTimeoutMillis: Int,
      maxResponseBytes: Long,
    ): HttpResponse {
      requested += url
      budget = maxResponseBytes
      val body = bodies[url] ?: return HttpResponse(404)
      return HttpResponse(200, emptyMap(), body)
    }
  }

  @Test
  fun `a file already on disk is read from disk and nothing is fetched`(@TempDir dir: File) {
    File(dir, "data").mkdirs()
    File(dir, "data/barley.json").writeText("""[{"yield":27}]""")
    val transport = Recorder()

    val loader = VegaDataLoaders.directoryThenNetwork(dir, transport = transport)

    assertEquals("""[{"yield":27}]""", loader.load("data/barley.json"))
    assertTrue(transport.requested.isEmpty(), "should not have reached the network")
  }

  @Test
  fun `a file that is not on disk is fetched from the base URL`(@TempDir dir: File) {
    val url = "${VegaDataLoaders.VEGA_EXAMPLE_DATA_BASE}data/barley.json"
    val transport = Recorder(mapOf(url to """[{"yield":27}]"""))

    val loader = VegaDataLoaders.directoryThenNetwork(dir, transport = transport)

    assertEquals("""[{"yield":27}]""", loader.load("data/barley.json"))
    assertEquals(listOf(url), transport.requested)
  }

  @Test
  fun `caching writes the fetched file where the next run will find it`(@TempDir dir: File) {
    val url = "${VegaDataLoaders.VEGA_EXAMPLE_DATA_BASE}data/barley.json"
    val transport = Recorder(mapOf(url to """[{"yield":27}]"""))
    val loader =
      VegaDataLoaders.directoryThenNetwork(dir, cacheDownloads = true, transport = transport)

    loader.load("data/barley.json")
    assertEquals("""[{"yield":27}]""", File(dir, "data/barley.json").readText())

    // The second read is served from the file the first one left behind.
    loader.load("data/barley.json")
    assertEquals(1, transport.requested.size, "should have fetched once, then read from disk")
  }

  @Test
  fun `the base URL's host is the allowlist, so a relative path cannot reach elsewhere`(
    @TempDir dir: File
  ) {
    val transport = Recorder()
    val loader = VegaDataLoaders.directoryThenNetwork(dir, transport = transport)

    // An absolute URL in a specification is not covered by the base and is refused by domain.
    val failure =
      assertThrows<LoadDeniedException> { loader.load("https://example.invalid/data/secrets.json") }
    assertTrue(failure.message!!.contains("example.invalid"), failure.message!!)
    assertTrue(transport.requested.isEmpty(), "a denied domain must not be contacted")
  }

  @Test
  fun `a path climbing out of the directory is refused before anything is read`(
    @TempDir dir: File
  ) {
    val loader = VegaDataLoaders.directoryThenNetwork(dir, transport = Recorder())
    // Refused by the file loader for leaving the tree, then by the HTTP one for resolving above the
    // base — so it fails, and the message says both.
    val failure = assertThrows<LoadDeniedException> { loader.load("../../../etc/passwd") }
    assertFalse(failure.message.isNullOrEmpty())
  }

  // ---- the composition itself ------------------------------------------------

  private class Fixed(private val body: String?) : DataLoader {
    override fun sanitize(uri: String): String =
      if (body != null) uri else throw LoadDeniedException("nothing here for '$uri'")

    override fun load(uri: String): String = body ?: throw LoadDeniedException("nothing here")
  }

  @Test
  fun `the first loader that will serve the URI wins`() {
    assertEquals("second", FallbackDataLoader(Fixed(null), Fixed("second")).load("x"))
  }

  @Test
  fun `sanitize returns its input, so passing it back to load still works`() {
    val loader = FallbackDataLoader(Fixed(null), Fixed("body"))
    val once = loader.sanitize("data/x.json")
    assertEquals("data/x.json", once)
    assertEquals(once, loader.sanitize(once))
    assertEquals("body", loader.load(once))
  }

  @Test
  fun `a refusal names every reason, not just the last`() {
    val failure = assertThrows<LoadDeniedException> { FallbackDataLoader(Fixed(null)).load("x") }
    assertTrue(failure.message!!.contains("No loader would serve 'x'"), failure.message!!)
    assertTrue(failure.message!!.contains("nothing here"), failure.message!!)
  }

  @Test
  fun `a broken socket is a failure, not a reason to try the next loader`() {
    // A timeout means "this did not work", not "this is not allowed". Falling through would hide an
    // outage behind a policy message, and would silently serve stale data from a later loader.
    val broken =
      object : DataLoader {
        override fun sanitize(uri: String): String = uri

        override fun load(uri: String): String = throw IOException("connection reset")
      }
    assertThrows<IOException> { FallbackDataLoader(broken, Fixed("fallback")).load("x") }
  }
}
