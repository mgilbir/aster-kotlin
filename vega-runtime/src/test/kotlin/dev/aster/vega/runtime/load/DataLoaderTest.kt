package dev.aster.vega.runtime.load

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The loader policy.
 *
 * This is the one part of the engine where being wrong is a security problem rather than a wrong
 * chart, so it is tested as policy — decisions, not fetches. That is what [DataLoader.sanitize]
 * being separate from [DataLoader.load] buys: every rule below is checked without a socket.
 *
 * The threat is concrete. A specification is data, often pasted data, and a `url` in it asks this
 * process to fetch an address the specification chose. `169.254.169.254` is the cloud metadata
 * endpoint; `localhost` is whatever the host happens to run.
 */
class DataLoaderTest {

  /** Records what was asked for, so a test can prove a request was never made. */
  private class Recorder(private val responses: Map<String, HttpResponse> = emptyMap()) :
    HttpTransport {
    val requested = mutableListOf<String>()

    /** The budget the loader handed down, so a test can prove it was passed at all. */
    var budget: Long = -1

    override fun get(
      url: String,
      connectTimeoutMillis: Int,
      readTimeoutMillis: Int,
      maxResponseBytes: Long,
    ): HttpResponse {
      requested += url
      budget = maxResponseBytes
      return responses[url] ?: HttpResponse(200, emptyMap(), "ok")
    }
  }

  // ---- the default -----------------------------------------------------------

  @Test
  fun `nothing loads unless a host opts in`() {
    val failure = assertThrows<LoadDeniedException> { DenyLoader.sanitize("https://example.com/x") }
    assertTrue(failure.message!!.contains("no data loader is configured"), failure.message!!)
    assertThrows<LoadDeniedException> { DenyLoader.load("https://example.com/x") }
  }

  // ---- scheme, userinfo, host ------------------------------------------------

  @Test
  fun `only http and https are allowed`() {
    val loader = HttpDataLoader(Recorder())
    for (uri in
      listOf("file:///etc/passwd", "ftp://host/x", "jar:file:///x", "javascript:alert(1)")) {
      val failure = assertThrows<LoadDeniedException>(uri) { loader.sanitize(uri) }
      assertTrue(failure.message!!.contains("not allowed"), failure.message!!)
    }
    assertEquals("https://example.com/x", loader.sanitize("https://example.com/x"))
  }

  /** Credentials in a URL are a way to make a request look like it came from someone else. */
  @Test
  fun `userinfo is refused`() {
    val loader = HttpDataLoader(Recorder())
    assertThrows<LoadDeniedException> { loader.sanitize("https://user:pass@example.com/x") }
  }

  // ---- the allowlist ---------------------------------------------------------

  @Test
  fun `an allowlist admits only the domains it names`() {
    val loader = HttpDataLoader(Recorder(), allowedDomains = listOf("example.com", "cdn.test"))
    assertEquals("https://example.com/a", loader.sanitize("https://example.com/a"))
    assertEquals("https://cdn.test/b", loader.sanitize("https://CDN.test/b"))
    val failure = assertThrows<LoadDeniedException> { loader.sanitize("https://evil.test/c") }
    assertTrue(failure.message!!.contains("not in the allowed list"), failure.message!!)
  }

  /** A subdomain is a different host, and matching it loosely is how allowlists leak. */
  @Test
  fun `an allowlist does not admit subdomains of what it names`() {
    val loader = HttpDataLoader(Recorder(), allowedDomains = listOf("example.com"))
    assertThrows<LoadDeniedException> {
      loader.sanitize("https://evil.example.com.attacker.test/x")
    }
    assertThrows<LoadDeniedException> { loader.sanitize("https://sub.example.com/x") }
  }

  @Test
  fun `an empty allowlist admits every domain`() {
    val loader = HttpDataLoader(Recorder())
    assertEquals("https://anything.test/x", loader.sanitize("https://anything.test/x"))
  }

  // ---- relative references ---------------------------------------------------

  @Test
  fun `a relative reference needs a base url and resolves against it`() {
    assertThrows<LoadDeniedException> { HttpDataLoader(Recorder()).sanitize("data/x.json") }
    val loader = HttpDataLoader(Recorder(), baseUrl = "https://example.com/docs/index.html")
    assertEquals("https://example.com/docs/data/x.json", loader.sanitize("data/x.json"))
    assertEquals("https://example.com/x.json", loader.sanitize("../x.json"))
    assertEquals("https://example.com/abs.json", loader.sanitize("/abs.json"))
  }

  /** A relative reference must not be able to climb out of the host it was resolved against. */
  @Test
  fun `dot segments cannot escape the base host`() {
    val loader = HttpDataLoader(Recorder(), baseUrl = "https://example.com/a/b/c")
    assertEquals("https://example.com/x", loader.sanitize("../../../../x"))
  }

  /** An absolute reference wins over the base, and is still checked against the allowlist. */
  @Test
  fun `a base url does not admit an otherwise denied absolute reference`() {
    val loader =
      HttpDataLoader(
        Recorder(),
        allowedDomains = listOf("example.com"),
        baseUrl = "https://example.com/",
      )
    assertThrows<LoadDeniedException> { loader.sanitize("https://evil.test/x") }
  }

  // ---- private networks ------------------------------------------------------

  @Test
  fun `private and loopback literals are refused when the rule is on`() {
    val loader = HttpDataLoader(Recorder(), blockPrivateNetworks = true)
    for (host in
      listOf("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.169.254", "0.0.0.0")) {
      val failure = assertThrows<LoadDeniedException>(host) { loader.sanitize("http://$host/x") }
      assertTrue(failure.message!!.contains("private or loopback"), failure.message!!)
    }
    // A public literal still passes.
    assertEquals("http://93.184.216.34/x", loader.sanitize("http://93.184.216.34/x"))
  }

  /** The rule is off by default, so local development keeps working. */
  @Test
  fun `private addresses are allowed when the rule is off`() {
    assertEquals("http://127.0.0.1/x", HttpDataLoader(Recorder()).sanitize("http://127.0.0.1/x"))
  }

  /**
   * A name that cannot be resolved cannot be checked, so it is refused rather than assumed safe.
   */
  @Test
  fun `an unresolvable name is refused rather than allowed`() {
    val loader = HttpDataLoader(Recorder(), blockPrivateNetworks = true)
    val failure = assertThrows<LoadDeniedException> { loader.sanitize("http://nowhere.invalid/x") }
    assertTrue(failure.message!!.contains("could not be resolved"), failure.message!!)
  }

  @Test
  fun `a name resolving to a private address is refused`() {
    val loader =
      HttpDataLoader(
        Recorder(),
        blockPrivateNetworks = true,
        resolver = { listOf("10.1.2.3") },
      )
    assertThrows<LoadDeniedException> { loader.sanitize("http://inside.test/x") }
  }

  // ---- redirects -------------------------------------------------------------

  /**
   * The case a check on the initial URL alone misses entirely: an allowed host handing back a
   * `Location` pointing somewhere denied.
   */
  @Test
  fun `a redirect to a denied domain is refused`() {
    val transport =
      Recorder(
        mapOf(
          "https://example.com/a" to
            HttpResponse(302, mapOf("Location" to "https://evil.test/secret"))
        )
      )
    val loader = HttpDataLoader(transport, allowedDomains = listOf("example.com"))
    val failure = assertThrows<LoadDeniedException> { loader.load("https://example.com/a") }
    assertTrue(failure.message!!.contains("not in the allowed list"), failure.message!!)
    // The denied hop was never requested.
    assertEquals(listOf("https://example.com/a"), transport.requested)
  }

  @Test
  fun `a redirect within the allowlist is followed`() {
    val transport =
      Recorder(
        mapOf(
          "https://example.com/a" to HttpResponse(302, mapOf("Location" to "/b")),
          "https://example.com/b" to HttpResponse(200, emptyMap(), "arrived"),
        )
      )
    val loader = HttpDataLoader(transport, allowedDomains = listOf("example.com"))
    assertEquals("arrived", loader.load("https://example.com/a"))
  }

  @Test
  fun `a redirect loop stops rather than spinning`() {
    val transport =
      Recorder(mapOf("https://example.com/a" to HttpResponse(302, mapOf("Location" to "/a"))))
    val failure =
      assertThrows<LoadDeniedException> { HttpDataLoader(transport).load("https://example.com/a") }
    assertTrue(failure.message!!.contains("redirects"), failure.message!!)
  }

  // ---- the response cap ------------------------------------------------------

  /** Truncating would give a wrong chart and no error, so an oversized response fails. */
  @Test
  fun `a response over the cap fails rather than truncating`() {
    val body = "x".repeat(1000)
    val transport = Recorder(mapOf("https://example.com/a" to HttpResponse(200, emptyMap(), body)))
    val loader = HttpDataLoader(transport, maxResponseBytes = 100)
    val failure = assertThrows<LoadDeniedException> { loader.load("https://example.com/a") }
    assertTrue(failure.message!!.contains("exceeds"), failure.message!!)

    assertEquals(
      body,
      HttpDataLoader(transport, maxResponseBytes = -1).load("https://example.com/a"),
    )
  }

  @Test
  fun `a non-success status is an error, not an empty dataset`() {
    val transport = Recorder(mapOf("https://example.com/a" to HttpResponse(404)))
    val failure =
      assertThrows<LoadDeniedException> { HttpDataLoader(transport).load("https://example.com/a") }
    assertTrue(failure.message!!.contains("404"), failure.message!!)
  }

  /**
   * The cap is on **bytes**, and it is handed to the transport rather than only checked after.
   *
   * Checking a `String.length` caps UTF-16 units, which is a third of the bytes for a document of
   * CJK text and a half for one of emoji, so the real ceiling moved with the content. And a cap
   * applied after the read is not a cap at all: the whole response is already in memory by then,
   * which is the thing being defended against.
   */
  @Test
  fun `the cap is bytes, and the transport is told before it reads`() {
    val transport = Recorder()
    HttpDataLoader(transport, maxResponseBytes = 4096).load("https://example.com/a")
    assertEquals(4096L, transport.budget, "the budget must reach the transport, not just the check")

    // Three bytes each in UTF-8, one UTF-16 unit each: a length check would see 100 and pass.
    val cjk = "\u4e2d".repeat(100)
    val overrun = Recorder(mapOf("https://example.com/a" to HttpResponse(200, emptyMap(), cjk)))
    val failure =
      assertThrows<LoadDeniedException> {
        HttpDataLoader(overrun, maxResponseBytes = 150).load("https://example.com/a")
      }
    assertTrue(failure.message!!.contains("exceeds"), failure.message!!)
  }

  // ---- addresses that are private in another notation ------------------------

  /**
   * A private address written as IPv6 is still a private address.
   *
   * `::ffff:169.254.169.254` and `::ffff:a9fe:a9fe` are the same host as `169.254.169.254` — the
   * mapped form the dual-stack sockets on every platform this runs on will connect straight
   * through. A rule that only reads dotted quads reads none of them, so the metadata endpoint was
   * one notation away.
   */
  @Test
  fun `a private address in ipv6 notation is refused`() {
    val loader = HttpDataLoader(Recorder(), blockPrivateNetworks = true)
    for (host in
      listOf(
        "[::1]",
        "[::]",
        "[::ffff:127.0.0.1]",
        "[::ffff:169.254.169.254]",
        "[::ffff:a9fe:a9fe]",
        "[::ffff:10.0.0.1]",
        "[64:ff9b::169.254.169.254]",
        "[fe80::1]",
        "[fec0::1]",
        "[fd00::1]",
      )) {
      assertThrows<LoadDeniedException>("$host must be refused") {
        loader.sanitize("http://$host/latest/meta-data/")
      }
    }

    // A public IPv6 address is still allowed: the rule is about reach, not about notation.
    assertEquals(
      "http://[2606:4700:4700::1111]/x",
      loader.sanitize("http://[2606:4700:4700::1111]/x"),
    )
  }

  // ---- shapes that are not an address at all ---------------------------------

  /**
   * `http:///x` parses, has an empty host, and resolves to whatever the platform decides — on the
   * JVM, the local machine. There is nothing a specification could mean by it, so it is refused
   * rather than resolved.
   */
  @Test
  fun `a url with no host is refused`() {
    val loader = HttpDataLoader(Recorder())
    for (url in listOf("http:///x", "https:///x", "http://:80/x")) {
      assertThrows<LoadDeniedException>(url) { loader.sanitize(url) }
    }
  }

  /**
   * A port is a protocol in practice. `http://host:25/` is an SMTP conversation the attacker gets
   * to write the first line of, which is the classic cross-protocol request smuggle; browsers keep
   * a blocklist for exactly this and this one is a transcription of the shape of it.
   */
  @Test
  fun `ports that speak another protocol are refused`() {
    val loader = HttpDataLoader(Recorder())
    for (port in listOf(22, 25, 465, 587, 6379, 11211)) {
      assertThrows<LoadDeniedException>("port $port") {
        loader.sanitize("http://example.com:$port/x")
      }
    }
    assertEquals("http://example.com:8080/x", loader.sanitize("http://example.com:8080/x"))
    assertEquals("https://example.com:443/x", loader.sanitize("https://example.com:443/x"))
  }

  // ---- the contract ----------------------------------------------------------

  /**
   * `load` re-checks its input, so a loader whose `sanitize` returned a form it would itself reject
   * would refuse its own output. A file loader got this wrong once already.
   */
  @Test
  fun `sanitize is idempotent`() {
    val loader = HttpDataLoader(Recorder(), baseUrl = "https://example.com/docs/")
    val once = loader.sanitize("data/x.json")
    assertEquals(once, loader.sanitize(once))

    // An IPv6 literal is where this went wrong: the host is stored bare, so writing it back out
    // without its brackets produced `http://2606:4700:4700::1111/x`, whose authority reparses with
    // a port of `4700:4700::1111` — `sanitize` returning something `load` refuses.
    val v6 = "http://[2606:4700:4700::1111]:8080/x"
    assertEquals(v6, loader.sanitize(v6))
    assertEquals(v6, loader.sanitize(loader.sanitize(v6)))
  }

  /** `load` cannot be talked past by handing it something `sanitize` never saw. */
  @Test
  fun `load applies the policy itself`() {
    val transport = Recorder()
    val loader = HttpDataLoader(transport, allowedDomains = listOf("example.com"))
    assertThrows<LoadDeniedException> { loader.load("https://evil.test/x") }
    assertTrue(transport.requested.isEmpty())
  }
}
