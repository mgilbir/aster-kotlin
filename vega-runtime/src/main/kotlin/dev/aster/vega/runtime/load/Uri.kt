package dev.aster.vega.runtime.load

/**
 * The little of a URI this needs, parsed without `java.net`.
 *
 * A deliberate subset of RFC 3986: scheme, userinfo, host, port, path, query and fragment, plus
 * reference resolution against a base. That is everything the loader policy inspects and everything
 * a data URL uses. It is written out rather than delegated so the whole policy stays common Kotlin
 * — the point of the split is that only the socket is platform-specific, and a URI parser is not a
 * socket.
 *
 * What it deliberately does **not** do: percent-decoding, IDN, or normalising case anywhere except
 * the scheme and host. None of that changes an allow/deny decision, and each is a place to get
 * subtly wrong.
 */
internal data class Uri(
  val scheme: String? = null,
  val userInfo: String? = null,
  val host: String? = null,
  val port: Int? = null,
  val path: String = "",
  val query: String? = null,
  val fragment: String? = null,
) {

  val isAbsolute: Boolean
    get() = scheme != null

  override fun toString(): String = buildString {
    scheme?.let { append(it).append(':') }
    if (host != null) {
      append("//")
      userInfo?.let { append(it).append('@') }
      // [host] is stored bare, because every rule in the policy wants the address and not the
      // punctuation. RFC 3986 requires the brackets back on an `IP-literal` when it is written out,
      // and it is not cosmetic: without them `http://[::1]:80/x` renders as `http://::1:80/x`,
      // whose authority reparses with a port of `:1:80` — so `sanitize` returned something `load`
      // would refuse, and `DataLoaderTest.sanitize is idempotent` is the contract that says it may
      // not. A host is a literal exactly when it contains a colon; a registered name cannot.
      if (host.contains(':')) append('[').append(host).append(']') else append(host)
      port?.let { append(':').append(it) }
    }
    append(path)
    query?.let { append('?').append(it) }
    fragment?.let { append('#').append(it) }
  }

  /**
   * RFC 3986 reference resolution: what [reference] means when read relative to this.
   *
   * Only the branches a data URL can reach are implemented — an absolute reference, a
   * network-relative one, an absolute path, and a relative path — and each is the specification's
   * own rule rather than string concatenation, because `"data/x.json"` against
   * `"https://host/docs/index.html"` has to drop the last segment and `"../x"` has to climb.
   */
  fun resolve(reference: Uri): Uri {
    if (reference.isAbsolute) return reference
    if (reference.host != null) return reference.copy(scheme = scheme)
    val path =
      when {
        reference.path.isEmpty() -> this.path
        reference.path.startsWith("/") -> removeDotSegments(reference.path)
        else -> removeDotSegments(merge(this.path, reference.path))
      }
    return Uri(
      scheme = scheme,
      userInfo = userInfo,
      host = host,
      port = port,
      path = path,
      query = if (reference.path.isEmpty()) reference.query ?: this.query else reference.query,
      fragment = reference.fragment,
    )
  }

  private fun merge(base: String, reference: String): String =
    if (host != null && base.isEmpty()) "/$reference"
    else base.substringBeforeLast('/', "") + "/" + reference

  /** Collapses `.` and `..`, which is what stops a relative reference climbing out of a host. */
  private fun removeDotSegments(path: String): String {
    val out = ArrayDeque<String>()
    for (segment in path.split('/')) {
      when (segment) {
        "." -> Unit
        ".." -> if (out.isNotEmpty()) out.removeLast()
        else -> out.addLast(segment)
      }
    }
    val joined = out.joinToString("/")
    return if (path.startsWith("/") && !joined.startsWith("/")) "/$joined" else joined
  }

  companion object {
    /** @throws LoadDeniedException if the text is not a URI this can read. */
    fun parse(text: String): Uri {
      var rest = text
      var fragment: String? = null
      var query: String? = null

      rest
        .indexOf('#')
        .takeIf { it >= 0 }
        ?.let {
          fragment = rest.substring(it + 1)
          rest = rest.substring(0, it)
        }
      rest
        .indexOf('?')
        .takeIf { it >= 0 }
        ?.let {
          query = rest.substring(it + 1)
          rest = rest.substring(0, it)
        }

      var scheme: String? = null
      val colon = rest.indexOf(':')
      // A scheme must start with a letter, which is what keeps `data/x.json` and `C:/x` from
      // parsing as one.
      if (
        colon > 0 &&
          rest[0].isLetter() &&
          rest.take(colon).all { it.isLetterOrDigit() || it in "+-." }
      ) {
        scheme = rest.take(colon).lowercase()
        rest = rest.substring(colon + 1)
      }

      var userInfo: String? = null
      var host: String? = null
      var port: Int? = null
      if (rest.startsWith("//")) {
        rest = rest.substring(2)
        val slash = rest.indexOf('/').takeIf { it >= 0 } ?: rest.length
        var authority = rest.substring(0, slash)
        rest = rest.substring(slash)
        authority
          .indexOf('@')
          .takeIf { it >= 0 }
          ?.let {
            userInfo = authority.substring(0, it)
            authority = authority.substring(it + 1)
          }
        // A bracketed IPv6 literal contains colons that are not a port separator. An *unbracketed*
        // one is not a URI at all — `http://::1/x` has no way to say where the address stops — so
        // the second colon in a bare authority is a malformed port and is reported as one rather
        // than being cut somewhere arbitrary.
        val portSeparator =
          if (authority.startsWith("["))
            authority.indexOf(':', authority.indexOf(']').coerceAtLeast(0))
          else authority.indexOf(':')
        if (portSeparator >= 0) {
          val text = authority.substring(portSeparator + 1)
          port =
            text.toIntOrNull()
              ?: throw LoadDeniedException("URI '$text' has a port that is not a number")
          authority = authority.substring(0, portSeparator)
        }
        host = authority.removeSurrounding("[", "]").lowercase()
      }

      return Uri(scheme, userInfo, host, port, rest, query, fragment)
    }
  }
}
