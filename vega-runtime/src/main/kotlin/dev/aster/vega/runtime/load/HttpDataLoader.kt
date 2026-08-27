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
  /**
   * One GET, following no redirects.
   *
   * @param maxResponseBytes the **byte** budget, or a negative number for none. A transport must
   *   stop reading once it is exceeded and throw, rather than returning a truncated body: half a
   *   dataset is a wrong chart and no error. The budget is a parameter rather than a check the
   *   caller applies afterwards because by then the whole response is already in memory, which is
   *   the one thing the cap exists to prevent — the class documentation says a hostile server
   *   "cannot stream unbounded data into memory", and until this parameter existed it could.
   */
  public fun get(
    url: String,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    maxResponseBytes: Long,
  ): HttpResponse
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

    /**
     * Ports a data URL never legitimately names, and a request forgery often does.
     *
     * Not an allowlist — an ordinary data endpoint runs on any port a host chose — but the handful
     * where reaching them at all is the attack: a shell, a mail relay, a printer daemon. Browsers
     * block a longer list for the same reason (Fetch's "bad port" table); this is the subset a
     * chart could plausibly be pointed at.
     */
    private val REFUSED_PORTS =
      setOf(22, 23, 25, 465, 587, 445, 135, 137, 138, 139, 515, 6379, 11211)
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
      val response =
        transport.get(
          current.toString(),
          connectTimeoutMillis,
          readTimeoutMillis,
          maxResponseBytes,
        )
      if (response.status in 300..399) {
        val location =
          response.header("Location")
            ?: throw LoadDeniedException("Redirect from '$current' carried no Location header")
        // `> `, not `>= `: the message says ten and the check stopped at nine, so a chain of
        // exactly ten hops — which the documented limit permits — was refused with a sentence
        // saying it was not.
        if (++hops > MAX_REDIRECTS) {
          throw LoadDeniedException("Stopped after $MAX_REDIRECTS redirects loading '$uri'")
        }
        current = current.resolve(Uri.parse(location))
        continue
      }
      if (response.status !in 200..299) {
        throw LoadDeniedException("HTTP ${response.status} loading '$current'")
      }
      // Belt and braces, for a transport that ignores the budget it was handed — and in **bytes**,
      // which is what the option is named for. `body.length` counts UTF-16 code units, so a
      // 60 MiB response of ASCII passed a 64 MiB cap that a 40 MiB response of CJK failed.
      if (maxResponseBytes >= 0 && response.body.encodeToByteArray().size > maxResponseBytes) {
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
    // **Empty, not just absent.** `http:///path` parses to a host of `""`, which is not null — so
    // it passed the null check, matched no entry in a non-empty allowlist (correct) and passed an
    // *empty* allowlist outright, reaching a transport with nowhere to connect to.
    val host =
      uri.host?.takeIf { it.isNotEmpty() } ?: throw LoadDeniedException("URI '$uri' names no host")
    // A port is part of the address a policy is about: `http://localhost:9200/` and
    // `http://localhost:22/` are different requests, and neither the allowlist nor the
    // private-network rule looked at one. Refusing the ports that are never a data endpoint is a
    // narrower rule than an allowlist and stops the two that matter — a shell and a mail relay
    // reached from a chart.
    uri.port?.let { port ->
      if (port in REFUSED_PORTS) {
        throw LoadDeniedException(
          "Port $port is not one this loader will fetch from; it is a well-known service port " +
            "rather than a data endpoint"
        )
      }
    }

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
    val bare = address.lowercase().removeSurrounding("[", "]")
    if (bare.contains(':')) {
      // **An IPv6 literal may be an IPv4 address wearing a hat.** `::ffff:127.0.0.1` and
      // `::ffff:169.254.169.254` are the v4-mapped form, which every socket stack connects to as
      // plain IPv4 — so `[::ffff:169.254.169.254]` reached the cloud metadata endpoint straight
      // through a rule whose whole purpose is to stop that. `64:ff9b::/96` is NAT64, which is the
      // same trick with a different prefix. Both are unwrapped and re-checked as v4.
      mappedV4(bare)?.let {
        return isPrivate(it)
      }
      return bare == "::1" ||
        bare == "::" ||
        bare.startsWith("fe80") || // link-local
        bare.startsWith("fc") || // unique local
        bare.startsWith("fd") ||
        // `fec0::/10`, the deprecated site-local range. Deprecated is not unreachable: a stack that
        // still routes it routes it to somewhere on the local network.
        bare.startsWith("fec") ||
        bare.startsWith("fed") ||
        bare.startsWith("fee") ||
        bare.startsWith("fef")
    }
    val parts = bare.split('.').mapNotNull { it.toIntOrNull() }
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

  /**
   * The IPv4 address an IPv6 literal is standing in for, or null when it is not standing in for
   * one.
   *
   * Two prefixes, and both connect as IPv4: `::ffff:a.b.c.d` (and its all-hex spelling
   * `::ffff:7f00:1`) is the v4-mapped form every dual-stack socket produces, and `64:ff9b::a.b.c.d`
   * is NAT64. `::a.b.c.d` — the deprecated v4-compatible form — is covered too, since it is
   * likewise routed as v4.
   */
  private fun mappedV4(address: String): String? {
    val dotted = address.substringAfterLast(':')
    if (dotted.count { it == '.' } == 3 && dotted.split('.').all { it.toIntOrNull() != null }) {
      val prefix = address.removeSuffix(dotted).lowercase()
      if (prefix.endsWith("::ffff:") || prefix.endsWith("64:ff9b::") || prefix.endsWith("::")) {
        return dotted
      }
      return null
    }
    // The all-hex spelling of the same thing: `::ffff:7f00:1`.
    val hex = address.removePrefix("::ffff:").takeIf { it != address } ?: return null
    val groups = hex.split(':')
    if (groups.size != 2) return null
    val high = groups[0].toIntOrNull(16) ?: return null
    val low = groups[1].toIntOrNull(16) ?: return null
    if (high !in 0..0xFFFF || low !in 0..0xFFFF) return null
    return "${high shr 8}.${high and 0xFF}.${low shr 8}.${low and 0xFF}"
  }
}
