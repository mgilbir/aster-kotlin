package dev.aster.vega.model

/**
 * Structured report for anything unsupported, invalid or approximated.
 *
 * The engine must never silently ignore an operator it does not implement (PROJECT_BRIEF.md 3.3 and
 * 14); every such case produces one of these instead.
 */
public data class VegaDiagnostic(
  val severity: DiagnosticSeverity,
  val code: String,
  val message: String,
  val jsonPath: String? = null,
  val operator: String? = null,
  val cause: Throwable? = null,
) {
  override fun toString(): String = buildString {
    append(severity.name.uppercase())
    append(' ')
    append(code)
    append(": ")
    append(message)
    jsonPath?.let {
      append(" at ")
      append(it)
    }
    operator?.let {
      append(" (operator ")
      append(it)
      append(')')
    }
  }
}

public enum class DiagnosticSeverity {
  /** Informational; the chart is unaffected. */
  INFO,
  /** The chart renders, but not exactly as the specification asked. */
  WARNING,
  /** A construct could not be honoured; the surrounding chart still renders. */
  ERROR,
  /** The chart cannot be produced at all. */
  FATAL,
}

/**
 * Diagnostic codes. Codes are part of the public contract: callers match on them, so renaming one
 * is a breaking change.
 */
public object DiagnosticCodes {
  public const val PARSE_INVALID_JSON: String = "VEGA_PARSE_INVALID_JSON"
  public const val PARSE_UNKNOWN_MARK: String = "VEGA_PARSE_UNKNOWN_MARK"
  public const val PARSE_UNKNOWN_PROPERTY: String = "VEGA_PARSE_UNKNOWN_PROPERTY"
  public const val PARSE_MISSING_PROPERTY: String = "VEGA_PARSE_MISSING_PROPERTY"

  /**
   * A parsed document declares nothing that draws.
   *
   * Informational, because `{}` is valid Vega and upstream renders it as an empty surface. What is
   * not acceptable is silence: a host that reads "no diagnostics" as "there is a chart" cannot
   * otherwise tell an empty placeholder object apart from a chart that drew.
   */
  public const val PARSE_NOTHING_TO_DRAW: String = "VEGA_PARSE_NOTHING_TO_DRAW"

  public const val EXPRESSION_PARSE_ERROR: String = "VEGA_EXPRESSION_PARSE_ERROR"
  public const val EXPRESSION_UNSUPPORTED_FUNCTION: String = "VEGA_EXPRESSION_UNSUPPORTED_FUNCTION"

  /** A message a specification asked for itself, through `warn()`, `info()` or `debug()`. */
  public const val EXPRESSION_LOG: String = "VEGA_EXPRESSION_LOG"

  /**
   * A specification read `containerSize()` and no host size was supplied.
   *
   * Informational: `[null, null]` is what a browser answers outside a container and so what
   * upstream answers, and the chart is exactly the one upstream draws. What the code cannot say for
   * itself is that a specification which branches on its container took the "no container" arm,
   * which is the one thing a host looking at an unexpected layout needs told.
   */
  public const val EXPRESSION_CONTAINER_SIZE_UNANSWERED: String =
    "VEGA_EXPRESSION_CONTAINER_SIZE_UNANSWERED"

  public const val SCALE_UNSUPPORTED_TYPE: String = "VEGA_SCALE_UNSUPPORTED_TYPE"
  public const val SCALE_INVALID_DOMAIN: String = "VEGA_SCALE_INVALID_DOMAIN"

  public const val TRANSFORM_NOT_IMPLEMENTED: String = "VEGA_TRANSFORM_NOT_IMPLEMENTED"
  public const val TRANSFORM_INVALID_PARAMETER: String = "VEGA_TRANSFORM_INVALID_PARAMETER"

  public const val SIGNAL_CYCLE: String = "VEGA_SIGNAL_CYCLE"

  public const val RENDER_UNSUPPORTED_BLEND_MODE: String = "VEGA_RENDER_UNSUPPORTED_BLEND_MODE"
  public const val RENDER_UNSUPPORTED_NODE: String = "VEGA_RENDER_UNSUPPORTED_NODE"

  public const val EXPORT_IMAGE_UNRESOLVED: String = "VEGA_EXPORT_IMAGE_UNRESOLVED"
  public const val EXPORT_UNSUPPORTED_OPERATION: String = "VEGA_EXPORT_UNSUPPORTED_OPERATION"
}

/**
 * Accumulates diagnostics during a single parse, compile, render or export pass.
 *
 * Deliberately not thread-safe and not a singleton: one collector belongs to one pass, so its
 * lifetime is always visible at the call site (PROJECT_BRIEF.md 21, "avoid singleton caches whose
 * lifetime cannot be controlled").
 */
public class DiagnosticCollector {
  private val entries = mutableListOf<VegaDiagnostic>()

  public val diagnostics: List<VegaDiagnostic>
    get() = entries.toList()

  public val hasFatal: Boolean
    get() = entries.any { it.severity == DiagnosticSeverity.FATAL }

  public val hasErrors: Boolean
    get() = entries.any {
      it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
    }

  public fun add(diagnostic: VegaDiagnostic) {
    entries.add(diagnostic)
  }

  /**
   * Empties the collector.
   *
   * For a collector that is drained rather than read once: a chart's interaction reports as it
   * runs, and publishing the same message again on the next event would be worse than not
   * publishing it.
   */
  public fun clear() {
    entries.clear()
  }

  public fun info(
    code: String,
    message: String,
    jsonPath: String? = null,
    operator: String? = null,
  ) {
    add(VegaDiagnostic(DiagnosticSeverity.INFO, code, message, jsonPath, operator))
  }

  public fun warn(
    code: String,
    message: String,
    jsonPath: String? = null,
    operator: String? = null,
  ) {
    add(VegaDiagnostic(DiagnosticSeverity.WARNING, code, message, jsonPath, operator))
  }

  public fun error(
    code: String,
    message: String,
    jsonPath: String? = null,
    operator: String? = null,
    cause: Throwable? = null,
  ) {
    add(VegaDiagnostic(DiagnosticSeverity.ERROR, code, message, jsonPath, operator, cause))
  }

  public fun fatal(
    code: String,
    message: String,
    jsonPath: String? = null,
    operator: String? = null,
    cause: Throwable? = null,
  ) {
    add(VegaDiagnostic(DiagnosticSeverity.FATAL, code, message, jsonPath, operator, cause))
  }

  public fun addAll(other: Iterable<VegaDiagnostic>) {
    entries.addAll(other)
  }
}

/** Thrown only when a specification cannot produce any chart at all. */
public class VegaSpecException(
  public val diagnostic: VegaDiagnostic,
  cause: Throwable? = diagnostic.cause,
) : Exception(diagnostic.toString(), cause)
