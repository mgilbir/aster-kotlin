package dev.aster.vegalite

/**
 * Diagnostic codes for the Vega-Lite compiler.
 *
 * Nothing here is silently ignored (PROJECT_BRIEF.md 3.3 and 14): every construct the compiler does
 * not implement produces one of these, naming itself and saying what a specification can do
 * instead. A Vega-Lite specification is short and its defaults are large, so an unimplemented
 * default is exactly the kind of omission a reader would otherwise mistake for a rendering bug.
 */
public object VegaLiteDiagnostics {
  public const val NOT_VEGA_LITE: String = "VEGA_LITE_NOT_A_SPECIFICATION"
  public const val MISSING_MARK: String = "VEGA_LITE_MISSING_MARK"
  public const val UNSUPPORTED_MARK: String = "VEGA_LITE_UNSUPPORTED_MARK"
  public const val UNSUPPORTED_CHANNEL: String = "VEGA_LITE_UNSUPPORTED_CHANNEL"
  public const val UNSUPPORTED_ENCODING_PROPERTY: String = "VEGA_LITE_UNSUPPORTED_ENCODING_PROPERTY"
  public const val UNSUPPORTED_COMPOSITION: String = "VEGA_LITE_UNSUPPORTED_COMPOSITION"
  public const val UNSUPPORTED_TRANSFORM: String = "VEGA_LITE_UNSUPPORTED_TRANSFORM"
  public const val UNSUPPORTED_PARAMETER: String = "VEGA_LITE_UNSUPPORTED_PARAMETER"
  public const val UNSUPPORTED_TOP_LEVEL_PROPERTY: String = "VEGA_LITE_UNSUPPORTED_PROPERTY"
  public const val INVALID_ENCODING: String = "VEGA_LITE_INVALID_ENCODING"
  public const val INFERRED_TYPE: String = "VEGA_LITE_INFERRED_TYPE"

  /**
   * The specification declares a `$schema` this compiler does not implement.
   *
   * Reported rather than refused: a Vega-Lite 5 chart is very largely a Vega-Lite 6 chart, and a
   * host that has been handed one is better served by a drawing and a note than by nothing. What it
   * must not be is silent, which it was — `isVegaLite` accepts any URL containing "vega-lite" and
   * compiles it with version 6 rules, so a version 7 payload would have been compiled as though the
   * rules had not moved.
   */
  public const val SCHEMA_VERSION: String = "VEGA_LITE_SCHEMA_VERSION"

  /**
   * The document is larger than the compiler will walk — see `Limits`.
   *
   * Nesting or transform count, and in both cases upstream refuses the same document too. What is
   * new is that this one comes back as a diagnostic rather than as a `StackOverflowError` out of a
   * public entry point.
   */
  public const val LIMIT_EXCEEDED: String = "VEGA_LITE_LIMIT_EXCEEDED"

  /**
   * The compiler failed on this document. A defect here, not in the document.
   *
   * The backstop behind everything else: `compileJson` and `compile` are guarded, so a
   * specification that reaches a defect comes back carrying the exception rather than as a crash in
   * the host. This module takes **pasted text** — `VegaLiteInput.toVega` is a `String` — and before
   * this had no `try` in it anywhere.
   */
  public const val COMPILE_FAILED: String = "VEGA_LITE_COMPILE_FAILED"
}
