package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * **d3-scale's** own linear-scale tests, replayed against [LinearScale].
 *
 * A scale is the arithmetic under every mark's position, and d3's corpus for it is a *configuration
 * chain* rather than a call: `scaleLinear().domain([1, 2]).range([10, 20]).clamp(true)` and only
 * then a question. Those vectors exist here because the recorder learned to follow a chain — each
 * chainable call is remembered, and a call that answers something records the configuration that
 * produced it. `d3-scale` went from 55 recorded vectors to **1,123** with that one change.
 *
 * What is replayed is the part this engine models the same way: evaluating the scale, inverting it,
 * and its ticks — and `invertExtent`, which is what `invert()` means for a scale with buckets, and
 * which sat in the unmapped column as "reflection API" until someone read the entry again. A getter
 * (`domain()` with no arguments) is upstream's own reflection API and has no equivalent here, so it
 * is counted rather than compared.
 */
class UpstreamD3ScaleVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-scale.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  private fun number(element: kotlinx.serialization.json.JsonElement?): Double? =
    when (element) {
      is JsonPrimitive -> element.doubleOrNull
      is JsonObject ->
        when (element["\$"]?.jsonPrimitive?.content) {
          "NaN" -> Double.NaN
          "Infinity" -> Double.POSITIVE_INFINITY
          "-Infinity" -> Double.NEGATIVE_INFINITY
          else -> null
        }
      else -> null
    }

  /** A recorded `Date` as epoch milliseconds. */
  private fun instant(element: kotlinx.serialization.json.JsonElement?): Double? =
    (element as? JsonObject)
      ?.takeIf { it["\$"]?.jsonPrimitive?.content == "date" }
      ?.get("epochMillis")
      ?.jsonPrimitive
      ?.doubleOrNull

  private fun numbers(element: kotlinx.serialization.json.JsonElement?): List<Double>? =
    (element as? JsonArray)?.map { number(it) ?: return null }

  /** One configuration, read off a chain and the constructor arguments together. */
  private class Config {
    var domainNumbers: List<Double>? = null
    var domainStrings: List<String>? = null
    var domainQuoted = false
    var rangeNumbers: List<Double>? = null
    var rangeValues: List<VegaValue>? = null
    var clamp = false
    var nice: Int? = null
    var round = false
    var exponent = 1.0
    var base = 10.0
    var paddingInner = 0.0
    var paddingOuter = 0.0
    var align = 0.5
  }

  /**
   * Reads a chain and the constructor arguments into a configuration, or null when it says
   * something this engine takes as a specification property rather than a chained call.
   *
   * d3 accepts the same settings three ways — `scaleLinear(range)`, `scaleLinear(domain, range)`
   * and `.domain(...).range(...)` — and a test uses whichever is shortest, so all three are read
   * here.
   */
  private fun configure(vector: JsonObject): Config? {
    val config = Config()
    fun setDomain(element: kotlinx.serialization.json.JsonElement?): Boolean {
      val array = element as? JsonArray ?: return false
      // A time scale's domain is `Date`s, recorded as `{"$": "date", "epochMillis": …}`; this
      // engine
      // holds instants as milliseconds, which is the same number.
      val dates = array.map { instant(it) }
      if (dates.all { it != null }) config.domainNumbers = dates.filterNotNull()
      numbers(array)?.let { config.domainNumbers = it }
      config.domainStrings = array.map { (it as? JsonPrimitive)?.content ?: return false }
      // Whether the *JSON* held strings, which `"10"` parsing as a number would otherwise hide.
      config.domainQuoted = array.all { (it as? JsonPrimitive)?.isString == true }
      return true
    }
    fun setRange(element: kotlinx.serialization.json.JsonElement?): Boolean {
      val array = element as? JsonArray ?: return false
      numbers(array)?.let { config.rangeNumbers = it }
      config.rangeValues = array.map { value ->
        number(value)?.let { VegaValue.Num(it) }
          ?: (value as? JsonPrimitive)?.content?.let { VegaValue.Str(it) }
          ?: return false
      }
      return true
    }

    val constructed = vector["constructedWith"] as? JsonArray
    when (constructed?.size) {
      1 -> if (!setRange(constructed[0])) return null
      2 -> {
        if (!setDomain(constructed[0])) return null
        if (!setRange(constructed[1])) return null
      }
      else -> Unit
    }
    for (step in (vector["chain"] as? JsonArray).orEmpty()) {
      val parts = step as? JsonArray ?: return null
      val method = (parts.firstOrNull() as? JsonPrimitive)?.content ?: return null
      val args = parts.getOrNull(1) as? JsonArray
      when (method) {
        "domain" -> if (!setDomain(args?.getOrNull(0))) return null
        "range" -> if (!setRange(args?.getOrNull(0))) return null
        "clamp" ->
          config.clamp = (args?.getOrNull(0) as? JsonPrimitive)?.booleanOrNull ?: return null
        "round" ->
          config.round = (args?.getOrNull(0) as? JsonPrimitive)?.booleanOrNull ?: return null
        "nice" -> config.nice = number(args?.getOrNull(0))?.toInt() ?: DEFAULT_NICE
        "exponent" -> config.exponent = number(args?.getOrNull(0)) ?: return null
        "base" -> config.base = number(args?.getOrNull(0)) ?: return null
        "padding" -> {
          val padding = number(args?.getOrNull(0)) ?: return null
          config.paddingInner = padding
          config.paddingOuter = padding
        }
        "paddingInner" -> config.paddingInner = number(args?.getOrNull(0)) ?: return null
        "paddingOuter" -> config.paddingOuter = number(args?.getOrNull(0)) ?: return null
        "align" -> config.align = number(args?.getOrNull(0)) ?: return null
        // `interpolate`, `unknown`, `rangeRound` and the reflection getters are configured
        // differently here — a Vega scale takes them as specification properties.
        else -> return null
      }
    }
    return config
  }

  /** Answers the question a vector asks, or null when this engine has no equivalent for it. */
  private fun answer(kind: String, method: String, config: Config, args: JsonArray): String? {
    // A **log** scale's default domain is [1, 10], not [0, 1]: zero has no logarithm, and taking
    // the
    // usual default made every unconfigured log scale answer NaN.
    val domain =
      config.domainNumbers ?: if (kind == "scaleLog") listOf(1.0, 10.0) else listOf(0.0, 1.0)
    // d3's own default range is `[0, 1]`, and `ticks` never consults the range at all — so
    // `scaleLinear().ticks(10)` is a perfectly ordinary question that this adapter was answering by
    // building a scale with **no** range and letting the constructor throw. 135 vectors were lost
    // that way, and lost quietly, because a throw was filed as "unmapped". A range that *was* given
    // but is not numeric — a colour ramp — is a different case, and `rangeValues` tells them apart.
    val range =
      config.rangeNumbers ?: if (config.rangeValues == null) listOf(0.0, 1.0) else emptyList()
    // `nice()` is **not one operation**: a log scale rounds outwards to powers of its base, where
    // a linear one rounds to a tick step. Nicing a log domain linearly took `[1.5, 50]` to
    // `[0, 50]`, and zero has no logarithm — so the scale answered NaN for every value.
    val niced =
      config.nice?.let {
        if (kind == "scaleLog") Ticks.niceLog(domain, config.base) else Ticks.nice(domain, it)
      } ?: domain
    // Checked **before** building, not after. A range of colours or strings — `range(["red",
    // "blue"])` — is a different scale here, and asking the continuous one for it threw out of the
    // constructor rather than reaching the guard that meant to skip it.
    if (kind in CONTINUOUS && (domain.size < 2 || range.size < 2)) return null
    val continuous: InvertibleScale? =
      when (kind) {
        "scaleLinear" ->
          LinearScale("replay", niced, range, clamp = config.clamp, round = config.round)
        "scalePow" ->
          PowScale("replay", niced, range, exponent = config.exponent, clamp = config.clamp)
        "scaleSqrt" -> PowScale("replay", niced, range, exponent = 0.5, clamp = config.clamp)
        "scaleLog" -> LogScale("replay", niced, range, base = config.base, clamp = config.clamp)
        "scaleSymlog" -> SymlogScale("replay", niced, range, clamp = config.clamp)
        else -> null
      }
    if (continuous != null) {
      // A range of **colours or strings** — `range(["red", "blue"])`, `range(["0px", "2px"])` — is
      // a
      // different scale here: this engine puts colour ramps in `SequentialColorScale` and never
      // interpolates a string. A domain with more than two stops is upstream's polylinear scale,
      // modelled apart as well. Both are counted rather than called divergences — and both apply
      // only to the *continuous* families, which is why the check sits here rather than above:
      // `quantize` and `threshold` are supposed to have value ranges.
      // A polylinear domain is no longer set aside: this engine interpolates piece by piece as
      // upstream does, and takes `min(domain, range)` stops when the two differ in length.
      if (domain.size < 2 || range.size < 2) return null
      return when (method) {
        "(call)" ->
          number(args.getOrNull(0))?.let { show(continuous.scale(VegaValue.Num(it)).asDouble()) }
        "invert" -> number(args.getOrNull(0))?.let { show(continuous.invert(it)) }
        "ticks" -> {
          val requested = number(args.getOrNull(0))
          if (requested != null && (!requested.isFinite() || requested > MAX_TICKS)) return null
          when (continuous) {
            is LinearScale -> continuous.ticks(requested?.toInt() ?: DEFAULT_TICKS)
            is LogScale -> continuous.ticks(requested?.toInt() ?: DEFAULT_TICKS)
            is PowScale -> continuous.ticks(requested?.toInt() ?: DEFAULT_TICKS)
            else -> return null
          }.joinToString(",") { show(it) }
        }
        else -> null
      }
    }
    return when (kind) {
      "scaleBand",
      "scalePoint" -> {
        val values = config.domainStrings ?: return null
        if (method != "(call)" || range.size < 2) return null
        val at = (args.getOrNull(0) as? JsonPrimitive)?.content ?: return null
        val scale =
          if (kind == "scaleBand") {
            BandScale(
              "replay",
              values,
              range,
              paddingInner = config.paddingInner,
              paddingOuter = config.paddingOuter,
              align = config.align,
              round = config.round,
            )
          } else {
            PointScale(
              "replay",
              values,
              range,
              padding = config.paddingOuter,
              align = config.align,
              round = config.round,
            )
          }
        show(scale.scale(VegaValue.Str(at)).asDouble())
      }
      "scaleTime",
      "scaleUtc" -> {
        // A time scale is a linear scale over instants; the zone is the whole difference between
        // the
        // two names, and it decides where a tick lands rather than where a value does.
        if (range.size != 2 || (config.domainNumbers?.size ?: 0) != 2) return null
        val zone =
          if (kind == "scaleUtc") kotlinx.datetime.TimeZone.UTC
          else kotlinx.datetime.TimeZone.currentSystemDefault()
        val scale =
          TimeScale("replay", config.domainNumbers!!, range, zone = zone, clamp = config.clamp)
        when (method) {
          "(call)" ->
            (instant(args.getOrNull(0)) ?: number(args.getOrNull(0)))?.let {
              show(scale.scale(VegaValue.Num(it)).asDouble())
            }
          "invert" -> number(args.getOrNull(0))?.let { show(scale.invert(it)) }
          else -> null
        }
      }
      "scaleOrdinal" -> {
        val values = config.domainStrings ?: return null
        val outputs = config.rangeValues ?: return null
        if (method != "(call)") return null
        val at = (args.getOrNull(0) as? JsonPrimitive)?.content ?: return null
        val answered = OrdinalScale("replay", values, outputs).scale(VegaValue.Str(at))
        if (answered is VegaValue.Str) answered.value else show(answered.asDouble())
      }
      "scaleQuantile",
      "scaleQuantize",
      "scaleThreshold" -> {
        val values = config.rangeValues ?: return null
        if (method != "(call)" && method != "invertExtent") return null
        val scale0 =
          when (kind) {
            "scaleQuantize" ->
              QuantizeScale("replay", config.domainNumbers ?: listOf(0.0, 1.0), values)
            "scaleQuantile" -> QuantileScale("replay", config.domainNumbers ?: return null, values)
            else -> ThresholdScale("replay", config.domainNumbers ?: emptyList(), values)
          }
        // `invertExtent` runs a bucketed scale backwards to the stretch of domain that maps to a
        // range value — what `invert()` means for a scale that has buckets rather than a gradient.
        if (method == "invertExtent") {
          val asked =
            (args.getOrNull(0) as? JsonPrimitive)?.let { p ->
              if (p.isString) VegaValue.Str(p.content)
              else p.doubleOrNull?.let { VegaValue.Num(it) }
            } ?: return null
          val extent = scale0.invertExtent(asked)
          return if (extent == null) "NaN,NaN"
          else
            "${extent.first?.let { show(it) } ?: "null"},${extent.second?.let { show(it) } ?: "null"}"
        }
        val at = number(args.getOrNull(0)) ?: return null
        val scale =
          when (kind) {
            "scaleQuantize" ->
              QuantizeScale("replay", config.domainNumbers ?: listOf(0.0, 1.0), values)
            // A quantile scale's domain is the **samples themselves**, not an extent: the cut
            // points
            // are its quantiles, which is why a skewed column gets narrow buckets where it is
            // dense.
            "scaleQuantile" -> QuantileScale("replay", config.domainNumbers ?: return null, values)
            else -> ThresholdScale("replay", config.domainNumbers ?: emptyList(), values)
          }
        val answered = scale.scale(VegaValue.Num(at))
        if (answered is VegaValue.Str) answered.value else show(answered.asDouble())
      }
      else -> null
    }
  }

  @Test
  fun `d3-scale's own linear vectors replay against LinearScale`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()
    for (vector in vectors) {
      val fn = vector["fn"]?.jsonPrimitive?.content ?: continue
      if (!fn.endsWith("()")) {
        unmapped.merge("$fn (the construction, not a question)", 1, Int::plus)
        continue
      }
      val kind = fn.removeSuffix("()")
      if (kind !in REPLAYED_SCALES) {
        unmapped.merge("$kind (no equivalent scale here)", 1, Int::plus)
        continue
      }
      val method = vector["method"]?.jsonPrimitive?.content ?: "(call)"
      if (method !in REPLAYED_METHODS) {
        unmapped.merge(
          when (method) {
            // Not reflection, and worth saying so: `tickFormat` returns a **formatter**, which a
            // vector records as `{$: function}` and cannot replay. The behaviour behind it — a log
            // axis labelling only the ticks whose mantissa is within `base * count / ticks`, so a
            // four-decade axis shows the powers of ten alone — is pinned by
            // `log-axis-labels.vg.json` instead, and this engine already had it right.
            "tickFormat" -> "tickFormat (returns a formatter; see log-axis-labels.vg.json)"
            else -> "$method (upstream's reflection API, not modelled here)"
          },
          1,
          Int::plus,
        )
        continue
      }
      val config = configure(vector)
      if (config == null) {
        unmapped.merge(
          "$kind (a setting this engine takes as a specification property)",
          1,
          Int::plus,
        )
        continue
      }
      val args = vector["args"] as? JsonArray ?: continue
      val expected =
        when (method) {
          "ticks" -> numbers(vector["result"])?.joinToString(",") { show(it) }
          // Two values, either of which may be absent: a threshold scale's outermost buckets are
          // unbounded, and upstream writes an unmatched range value as `[NaN, NaN]`.
          "invertExtent" ->
            (vector["result"] as? JsonArray)
              ?.take(2)
              ?.joinToString(",") { end ->
                when {
                  end is JsonObject && end["\$"]?.jsonPrimitive?.content == "NaN" -> "NaN"
                  end is JsonObject && end["\$"]?.jsonPrimitive?.content == "undefined" -> "null"
                  else -> number(end)?.let { show(it) } ?: "null"
                }
              }
              ?.takeIf { it.contains(',') }
          else ->
            number(vector["result"])?.let { show(it) }
              ?: (vector["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
      if (expected == null) {
        unmapped.merge("$kind.$method (an answer this comparison cannot render)", 1, Int::plus)
        continue
      }
      // A throw is a **failure**, not a gap. Counting it as unmapped is the harness quietly
      // excusing the engine, which is the one thing this comparison must never do: 135 crashes sat
      // in the unmapped column looking like features nobody had ported.
      val actual = runCatching {
        answer(kind, method, config, args)
      }
        .getOrElse {
          replayed++
          failures.add("$kind.$method$args threw ${it::class.simpleName}: ${it.message}")
          continue
        }
      if (actual == null) {
        unmapped.merge("$kind.$method (not modelled the same way here)", 1, Int::plus)
        continue
      }
      replayed++
      if (expected != actual) {
        failures.add(
          "$kind${describe(vector["chain"] as? JsonArray)}.$method($args): upstream $expected, ours $actual"
        )
      }
    }

    val ledger = StringBuilder("replayed $replayed of ${vectors.size} d3-scale vectors\n")
    unmapped.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-d3-scale-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }

    // Pinned exactly, like the transform and parser divergences before it.
    val known =
      json
        .parseToJsonElement(
          File(
              File(System.getProperty("user.dir")).parentFile,
              "test-fixtures/upstream-vectors/known-divergences.json",
            )
            .readText()
        )
        .jsonObject["divergences"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["kind"]?.jsonPrimitive?.content == "scale" }
        .map { it["signature"]!!.jsonPrimitive.content }
    assertEquals(
      known.sorted(),
      failures.map { it.substringBefore(": upstream") }.sorted(),
      "the set of scale divergences changed; update known-divergences.json",
    )
    assertTrue(replayed >= 330, "only $replayed vectors replayed; the harness must not shrink")
  }

  private fun describe(chain: JsonArray?): String =
    chain.orEmpty().joinToString("") { step ->
      val parts = step as? JsonArray ?: return@joinToString ""
      ".${(parts.firstOrNull() as? JsonPrimitive)?.content}(${(parts.getOrNull(1) as? JsonArray)?.joinToString(",") ?: ""})"
    }

  private fun show(value: Double?): String =
    when {
      value == null -> "null"
      value.isNaN() -> "NaN"
      !value.isFinite() -> value.toString()
      else -> {
        // Six decimals: upstream's own numbers carry the same floating-point dust this engine's do,
        // and comparing them exactly would compare the order of two multiplications.
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
      }
    }

  private companion object {
    /** The families this adapter builds as one continuous scale. */
    val CONTINUOUS = setOf("scaleLinear", "scalePow", "scaleSqrt", "scaleLog", "scaleSymlog")

    val REPLAYED_METHODS = setOf("(call)", "invert", "invertExtent", "ticks")

    /** The families this engine models the same way d3 does. */
    val REPLAYED_SCALES =
      setOf(
        "scaleLinear",
        "scalePow",
        "scaleSqrt",
        "scaleLog",
        "scaleSymlog",
        "scaleBand",
        "scalePoint",
        "scaleQuantize",
        "scaleThreshold",
        "scaleQuantile",
        "scaleOrdinal",
        "scaleTime",
        "scaleUtc",
      )
    const val DEFAULT_TICKS = 10
    const val DEFAULT_NICE = 10

    /** No chart asks for more, and comparing lists longer than this measures nothing. */
    const val MAX_TICKS = 10_000
  }
}
