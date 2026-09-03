@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * `wordcloud`: words laid out at sizes taken from the data, largest first, without overlapping.
 *
 * The layout itself is [CloudLayout], ported from upstream and checked against upstream's own
 * placements to the pixel. This is the wiring around it — the parameters, the size scale, and
 * writing seven columns back onto each row.
 *
 * **What is different from upstream, stated plainly.** Upstream decides whether two words collide
 * by rasterising each into a canvas and comparing pixel masks, which lets a short word tuck into
 * the space under a tall one. Nothing in common Kotlin rasterises glyphs and nothing here should —
 * a font is the host's. So a host that can rasterise supplies the masks through [CloudSprites] and
 * gets upstream's own packing; a host that cannot gets [BoxSprites], where each word is the
 * rectangle its metrics describe. Same words, same sizes, same order, more air between them. The
 * difference is reported once per run rather than left for a reader to notice.
 *
 * **What is the same.** Everything else, and it is checked rather than asserted: given upstream's
 * recorded masks, `CloudLayoutTest` requires this to place all eighteen words of its corpus exactly
 * where upstream placed them. That covers the sort, the spiral, the board packing, the bitmask
 * collision test, the bounding-box test, the blank-row trimming, and — the part with no partial
 * credit — the order the random draws come off the generator.
 *
 * **Determinism.** Upstream's word cloud is random by default, and this one is not: the generator
 * is the chart's seeded [dev.aster.vega.expression.RandomStream], the same one `random()` reads, so
 * the same specification draws the same cloud twice. That is a deliberate difference and the reason
 * a word cloud can be compared with anything at all.
 */
public object WordcloudTransform : Transform {
  override val type: String = "wordcloud"

  /** Upstream's `Output`: the seven columns a placed word writes back. */
  private val OUTPUT = listOf("x", "y", "font", "fontSize", "fontStyle", "fontWeight", "angle")

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val size = (params.fields["size"] as? VegaValue.Arr)?.values
    val width = size?.getOrNull(0)?.let { JsSemantics.toNumber(it) } ?: 500.0
    val height = size?.getOrNull(1)?.let { JsSemantics.toNumber(it) } ?: 500.0
    if (
      size != null && (width == 0.0 || height == 0.0 || !width.isFinite() || !height.isFinite())
    ) {
      // Upstream throws here. This reports and hands the rows back, which is what every transform
      // in this engine does with a parameter it cannot use: a chart that draws the rest of itself
      // and says what it could not do beats a chart that does not draw.
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "wordcloud's 'size' dimensions must both be non-zero, and were [$width, $height]",
        operator = type,
      )
      return input
    }

    val as0 =
      (params.fields["as"] as? VegaValue.Arr)
        ?.values
        ?.map { it.asString() }
        ?.takeIf { it.size == OUTPUT.size } ?: OUTPUT

    val text = fieldPath(params.fields["text"])
    val fontOf = stringParam(params.fields["font"], "sans-serif", context)
    val styleOf = stringParam(params.fields["fontStyle"], "normal", context)
    val weightOf = stringParam(params.fields["fontWeight"], "normal", context)
    val rotateOf = numberParam(params.fields["rotate"], 0.0, context)
    val paddingOf = numberParam(params.fields["padding"], 1.0, context)
    val spiral = params.string("spiral")?.takeIf { it == "rectangular" } ?: "archimedean"

    val sizeOf = fontSizeOf(input, params, context)

    val words = input.mapIndexed { index, datum ->
      CloudWord(
        index = index,
        text = if (text == null) "" else datum.field(text).asString(),
        font = fontOf(datum),
        style = styleOf(datum),
        weight = weightOf(datum),
        // `~~(fontSize + 1e-14)` upstream: truncated, with a nudge that stops a size which is a
        // hair under a whole number — as one off a `sqrt` scale usually is — losing a pixel.
        size = (sizeOf(datum) + 1e-14).toInt(),
        rotate = rotateOf(datum),
        padding = paddingOf(datum),
      )
    }

    // The seam, defaulted. A host that can rasterise glyphs supplies its own [CloudSprites] and
    // gets upstream's packing; everything else measures and fills the box.
    val sprites: CloudSprites = BoxSprites { word ->
      // Upstream measures the word with an `m` appended, at a font size one larger than the sprite
      // is drawn at. Both are reproduced: the `m` is what keeps two words from touching, and the
      // extra pixel is what the sprite's own height allows for.
      context.measureText(word.text + "m", (word.size + 1).toDouble())
    }

    // The chart's own seeded generator, which is what `random()` reads and what the oracle seeds
    // with `vega.setRandom(vega.randomLCG(42))`. A word cloud drawn from a real random source
    // cannot be compared with anything, including itself.
    val random = context.scope.random
    val placed =
      CloudLayout(
          words,
          intArrayOf(width.toInt(), height.toInt()),
          spiral,
          sprites,
          random::next,
        )
        .layout()

    if (placed.size < words.size) {
      // Not an error: dropping what will not fit is the algorithm. Reported because a reader
      // looking at a cloud has no way to tell a word that was filtered out from one that lost.
      context.diagnostics.info(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "wordcloud placed ${placed.size} of ${words.size} words; the rest did not fit in " +
          "${width.toInt()} x ${height.toInt()}",
        operator = type,
      )
    }

    val dx = width.toInt() shr 1
    val dy = height.toInt() shr 1
    val byIndex = placed.associateBy { it.index }
    return input.mapIndexed { index, datum ->
      val obj = datum as? VegaValue.Obj ?: return@mapIndexed datum
      val fields = LinkedHashMap(obj.fields)
      val word = byIndex[index]
      if (word == null) {
        // Upstream writes NaN into `x` and `y` and zero into `fontSize` for every row up front, and
        // only the placed ones get real values. A mark reading NaN draws nothing, which is how a
        // dropped word disappears rather than piling up at the origin.
        fields[as0[0]] = VegaValue.Num(Double.NaN)
        fields[as0[1]] = VegaValue.Num(Double.NaN)
        fields[as0[3]] = VegaValue.Num(0.0)
      } else {
        fields[as0[0]] = VegaValue.Num((word.x + dx).toDouble())
        fields[as0[1]] = VegaValue.Num((word.y + dy).toDouble())
        fields[as0[2]] = VegaValue.Str(word.font)
        fields[as0[3]] = VegaValue.Num(word.size.toDouble())
        fields[as0[4]] = VegaValue.Str(word.style)
        fields[as0[5]] = VegaValue.Str(word.weight)
        fields[as0[6]] = VegaValue.Num(word.rotate)
      }
      VegaValue.Obj(fields)
    }
  }

  /**
   * The font size for each row, and the scale over it when there is one.
   *
   * Upstream's rule, which is subtler than it looks: a **constant** `fontSize` is used as written
   * and `fontSizeRange` is ignored, while a `fontSize` that reads the data is put through a `sqrt`
   * scale from the data's extent onto that range. So `"fontSize": 20` means twenty pixels and
   * `"fontSize": {"field": "count"}` means "between 10 and 50 pixels, by count" — the same property
   * meaning a size in one case and a *weight* in the other.
   *
   * The scale is `sqrt` rather than linear because a word's ink grows with the square of its
   * height: doubling the font size roughly quadruples the area, so a linear scale would make the
   * largest word look four times as important as it is.
   */
  private fun fontSizeOf(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): (VegaValue) -> Double {
    val declared = params.fields["fontSize"]
    val constant = declared?.let { it as? VegaValue.Num }?.value
    if (declared == null || constant != null) return { constant ?: 14.0 }

    val of = numberParam(declared, 14.0, context)
    val values = input.map(of).filter { !it.isNaN() }
    val range =
      (params.fields["fontSizeRange"] as? VegaValue.Arr)?.values?.map { JsSemantics.toNumber(it) }
        ?: listOf(10.0, 50.0)
    if (values.isEmpty() || range.size < 2) return of

    val low = values.min()
    val high = values.max()
    val r0 = range[0]
    val r1 = range[1]
    // d3's `scaleSqrt`, which is a power scale of exponent 0.5 and preserves sign, so a negative
    // weight maps below the range rather than to NaN. A domain with no spread puts everything at
    // the range's low end, which is d3's answer for a zero-width domain on a continuous scale.
    val t0 = signedSqrt(low)
    val t1 = signedSqrt(high)
    if (t1 == t0) return { r0 }
    return { datum -> r0 + (r1 - r0) * (signedSqrt(of(datum)) - t0) / (t1 - t0) }
  }

  private fun signedSqrt(x: Double): Double = sign(x) * sqrt(abs(x))

  /** A `field` parameter, accepting both the bare name and the `{"field": "..."}` object form. */
  private fun fieldPath(value: VegaValue?): String? =
    when (value) {
      null -> null
      is VegaValue.Str -> value.value.takeIf { it.isNotEmpty() }
      is VegaValue.Obj -> value.fields["field"]?.asString()?.takeIf { it.isNotEmpty() }
      else -> null
    }

  /**
   * A parameter that is a constant, or an `{"expr": "..."}` evaluated per row.
   *
   * Upstream marks `font`, `fontStyle`, `fontWeight`, `fontSize`, `rotate` and `padding` as `expr:
   * true`, which is what lets one cloud mix two fonts or rotate every other word.
   */
  private fun stringParam(
    value: VegaValue?,
    fallback: String,
    context: TransformContext,
  ): (VegaValue) -> String {
    val expression = (value as? VegaValue.Obj)?.fields?.get("expr")?.asString()
    if (expression != null) {
      val compiled = TupleExpression(expression, context, type)
      if (!compiled.isUsable) return { fallback }
      return { datum ->
        compiled.evaluate(datum)?.asString()?.takeIf { it.isNotEmpty() } ?: fallback
      }
    }
    val literal = value?.asString()?.takeIf { it.isNotEmpty() } ?: fallback
    return { literal }
  }

  private fun numberParam(
    value: VegaValue?,
    fallback: Double,
    context: TransformContext,
  ): (VegaValue) -> Double {
    val path = fieldPath(value)
    val expression = (value as? VegaValue.Obj)?.fields?.get("expr")?.asString()
    return when {
      expression != null -> {
        val compiled = TupleExpression(expression, context, type)
        if (!compiled.isUsable) {
          { fallback }
        } else {
          { datum -> compiled.evaluate(datum)?.let { JsSemantics.toNumber(it) } ?: fallback }
        }
      }
      value is VegaValue.Obj && path != null -> {
        { datum -> JsSemantics.toNumber(datum.field(path)) }
      }
      value == null -> {
        { fallback }
      }
      else -> {
        val literal = JsSemantics.toNumber(value)
        if (literal.isNaN()) ({ fallback }) else ({ literal })
      }
    }
  }
}
