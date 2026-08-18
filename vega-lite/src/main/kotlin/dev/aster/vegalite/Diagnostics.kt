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
}
