package dev.aster.vega.runtime.load

/** One HTTP response, reduced to what the loader has to reason about. */
public data class HttpResponse(
  val status: Int,
  /** Only `Location` is read, but the shape stays general. Keys compare case-insensitively. */
  val headers: Map<String, String> = emptyMap(),
  val body: String = "",
) {
  public fun header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * The one platform seam: performing a single HTTP GET, following no redirects.
 *
 * Everything else about loading — parsing the URI, resolving it against a base, the allowlist, the
 * private-network rule, the redirect loop and the response cap — is common Kotlin in
 * [HttpDataLoader]. Keeping the seam this narrow is what makes the policy the same on every
 * platform rather than reimplemented per platform, which is exactly where security rules rot.
 *
 * **Must not follow redirects.** The loader follows them itself so it can apply the policy to every
 * hop; a transport that follows them silently would defeat that.
 */
public fun interface HttpTransport {
  public fun get(url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int): HttpResponse
}

/**
 * Resolves a hostname to IP addresses, for the private-network rule.
 *
 * A seam because name resolution is platform-specific. Returning an empty list means "could not
 * resolve", which the policy treats as a refusal rather than a pass.
 */
public fun interface HostResolver {
  public fun resolve(host: String): List<String>

  public companion object {
    /** Refuses to resolve, so [HttpDataLoader.blockPrivateNetworks] denies rather than allows. */
    public val Unavailable: HostResolver = HostResolver { emptyList() }
  }
}

/**
 * Fetches data over HTTP under a policy the host states explicitly.
 *
 * The controls are those of the Go loader in `mgilbir/aster`, and they exist because a Vega
 * specification is *data* — often data a user pasted — and a `url` in it asks this process to make
 * a request to an address the specification chose. Unconstrained, that is a server-side request
 * forgery primitive: `http://169.254.169.254/` is cloud credentials, `http://localhost:9200/` is
 * whatever the host happens to be running.
 *
 * What each control is actually for:
 * - [allowedDomains] — the blunt instrument, and the only one that really helps against a hostile
 *   specification. Empty means every domain: a real choice for trusted input, and the wrong one for
 *   untrusted.
 * - [baseUrl] — resolves relative URIs, which is how nearly every published example names its data.
 *   Without one a relative URI is refused rather than guessed at.
 * - [blockPrivateNetworks] — refuses hosts that resolve to loopback, link-local, private or
 *   unspecified addresses. Off by default so local development keeps working. It resolves at policy
 *   time, so **it does not by itself stop DNS rebinding**; pair it with [allowedDomains] for
 *   untrusted input.
 * - [maxResponseBytes] — a cap, so a hostile or broken server cannot stream unbounded data into
 *   memory. Exceeding it fails rather than truncating: half a dataset is a wrong chart and no
 *   error.
 *
 * The policy is re-applied to **every redirect hop**, which is the case that a check on the initial
 * URL alone misses entirely — an allowed host redirecting to a denied one.
 */
public class HttpDataLoader(
  private val transport: HttpTransport,
  private val allowedDomains: List<String> = emptyList(),
  private val baseUrl: String? = null,
  private val blockPrivateNetworks: Boolean = false,
  private val resolver: HostResolver = HostResolver.Unavailable,
  private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
  private val connectTimeoutMillis: Int = 10_000,
  private val readTimeoutMillis: Int = 30_000,
) : DataLoader {

  public companion object {
    /** 64 MiB, matching the Go implementation. A negative cap disables the limit. */
    public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 64L shl 20

    private const val MAX_REDIRECTS = 10
  }

  override fun sanitize(uri: String): String {
    val parsed = Uri.parse(uri)
    val resolved =
      if (parsed.isAbsolute) {
        parsed
      } else {
        val base =
          baseUrl
            ?: throw LoadDeniedException(
              "Relative URI '$uri' is not allowed: no base URL is configured, and guessing one " +
                "would mean fetching from an address nobody chose"
            )
        Uri.parse(base).resolve(parsed)
      }
    check(resolved)
    return resolved.toString()
  }

  override fun load(uri: String): String {
    // Re-checked rather than trusting the caller, so calling `load` directly is still safe.
    var current = Uri.parse(sanitize(uri))
    var hops = 0
    while (true) {
      check(current)
      val response = transport.get(current.toString(), connectTimeoutMillis, readTimeoutMillis)
      if (response.status in 300..399) {
        val location =
          response.header("Location")
            ?: throw LoadDeniedException("Redirect from '$current' carried no Location header")
        if (++hops >= MAX_REDIRECTS) {
          throw LoadDeniedException("Stopped after $MAX_REDIRECTS redirects loading '$uri'")
        }
        current = current.resolve(Uri.parse(location))
        continue
      }
      if (response.status !in 200..299) {
        throw LoadDeniedException("HTTP ${response.status} loading '$current'")
      }
      if (maxResponseBytes >= 0 && response.body.length > maxResponseBytes) {
        throw LoadDeniedException(
          "Response from '$current' exceeds $maxResponseBytes bytes; raise maxResponseBytes to " +
            "allow a larger payload"
        )
      }
      return response.body
    }
  }

  private fun check(uri: Uri) {
    if (uri.userInfo != null) {
      throw LoadDeniedException("URI for host '${uri.host}' carries userinfo, which is not allowed")
    }
    if (uri.scheme != "http" && uri.scheme != "https") {
      throw LoadDeniedException(
        "Scheme '${uri.scheme}' is not allowed in '$uri'; only http and https are"
      )
    }
    val host = uri.host ?: throw LoadDeniedException("URI '$uri' names no host")

    if (allowedDomains.isNotEmpty() && allowedDomains.none { it.equals(host, ignoreCase = true) }) {
      throw LoadDeniedException(
        "Domain '$host' is not in the allowed list (${allowedDomains.joinToString(", ")})"
      )
    }

    if (blockPrivateNetworks) {
      val addresses = if (isIpLiteral(host)) listOf(host) else resolver.resolve(host)
      if (addresses.isEmpty()) {
        throw LoadDeniedException(
          "Host '$host' could not be resolved, so it cannot be checked against the " +
            "private-network rule"
        )
      }
      addresses
        .firstOrNull { isPrivate(it) }
        ?.let {
          throw LoadDeniedException("Host '$host' resolves to '$it', a private or loopback address")
        }
    }
  }

  private fun isIpLiteral(host: String): Boolean =
    host.contains(':') ||
      host.split('.').let { it.size == 4 && it.all { p -> p.toIntOrNull() != null } }

  /**
   * Loopback, link-local, private and unspecified ranges, for IPv4 and IPv6.
   *
   * `169.254.0.0/16` is the one worth naming: it holds the cloud metadata endpoint, which is the
   * single most valuable thing an SSRF can reach.
   */
  private fun isPrivate(address: String): Boolean {
    if (address.contains(':')) {
      val v6 = address.lowercase().removeSurrounding("[", "]")
      return v6 == "::1" ||
        v6 == "::" ||
        v6.startsWith("fe80") || // link-local
        v6.startsWith("fc") || // unique local
        v6.startsWith("fd")
    }
    val parts = address.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val (a, b) = parts
    return a == 0 ||
      a == 127 || // loopback
      a == 10 || // private
      (a == 172 && b in 16..31) || // private
      (a == 192 && b == 168) || // private
      (a == 169 && b == 254) || // link-local, including cloud metadata
      a >= 224 // multicast and reserved
  }
}
