package dev.aster.vega.scene

/**
 * A specification's font as the CSS stack it is, read the same way by every renderer.
 *
 * `TextStyle.fontFamily` carries what the specification wrote, whole: `"Noto Sans, Chart Sans"`, or
 * `"sans-serif"`, or a single name. Nothing splits it before a text engine sees it, and until this
 * existed each engine split it differently — or not at all:
 *
 * - the Compose Multiplatform engine read the whole stack and let any entry match;
 * - the Apple renderer offered its resolver the **first** entry only, and nothing at all when that
 *   entry was a generic;
 * - the Android view offered the resolver the **unsplit string**, so a host that registered `Chart
 *   Sans` was asked for `"Noto Sans, Chart Sans"` and never answered.
 *
 * One specification therefore drew in three different faces on three hosts, with one registration.
 * Reported as #123.
 *
 * The rule here is the one those specifications are written in. Split on commas, trim spaces and
 * quotes, drop what is left empty, and offer the entries **in order** until something answers. A
 * generic is offered like any other name, because a host that registers `sans-serif` has said what
 * its sans is; an engine falls back to the platform's own generic only when nothing answered.
 *
 * It lives in `vega-scene` because every renderer already depends on it — including the Swift one,
 * which reaches it through the exported framework. One rule as one piece of code rather than four.
 */
public object FontStack {

  /**
   * The families a stack names, in order, trimmed of spaces and quotes.
   *
   * Empty for a stack that names nothing, which a caller treats as "ask the platform".
   */
  public fun families(stack: String): List<String> {
    // Split **outside quotes**. A family name is allowed to contain a comma, and CSS is why the
    // quotes are there: `"Foo, Bar", serif` names two families, and splitting on the comma first
    // named three, none of which a host could ever answer. A quote is a quote only where a name
    // starts, which is what keeps an apostrophe inside one — `Bob's Sans` — from opening a string.
    val names = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (char in stack) {
      when {
        quote != null -> if (char == quote) quote = null else current.append(char)
        (char == '"' || char == '\'') && current.isBlank() -> quote = char
        char == ',' -> {
          names.add(current.toString())
          current.setLength(0)
        }
        else -> current.append(char)
      }
    }
    names.add(current.toString())
    return names.map { it.trim() }.filter { it.isNotEmpty() }
  }

  /**
   * Whether a name is one of CSS's generic keywords rather than a face.
   *
   * Offered to a host resolver **anyway** — see the note above — and used by an engine to decide
   * what to fall back to once nothing has answered.
   */
  public fun isGeneric(name: String): Boolean = name.trim().lowercase() in GENERICS

  /** The generic keywords, lower-cased. */
  public val generics: Set<String>
    get() = GENERICS

  private val GENERICS =
    setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")
}
