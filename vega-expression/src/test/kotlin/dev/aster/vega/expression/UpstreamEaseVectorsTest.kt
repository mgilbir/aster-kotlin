package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asNumberOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.ulp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * **d3-ease's** own tests, replayed against the `ease*` expression functions Vega 6.4.0 added.
 *
 * Compared **exactly** wherever the curve is arithmetic and `sqrt` — quad, cubic, circle, bounce,
 * back, linear — because those are correctly rounded on every platform, [Ease] is transcribed
 * operation for operation so that they can be, and a curve right to twelve digits and wrong in the
 * last bit is one whose operations were reordered.
 *
 * The four families built on `pow`, `sin` and `cos` — poly, sin, exp, elastic — are compared to
 * [TRANSCENDENTAL_ULPS] units in the last place instead, and the reason is the platform, not the
 * port. Those functions are not correctly rounded: V8 uses a port of fdlibm, and the JVM's `Math`
 * intrinsics round differently **and differently per architecture**. Every one of these replayed
 * exactly on an ARM Mac, and on x86 Linux `easeSinIn(0.7)` came out one ulp off. Matching V8's last
 * bit everywhere needs a pure-Kotlin fdlibm, which would move every `sin` and `cos` in the engine
 * and is its own change.
 *
 * What is not replayed is named: the parametric builders — `easePolyIn.exponent(2)`,
 * `easeElasticIn.amplitude(1.5)` — which upstream does not expose to expressions, and an argument
 * that is an object with its own `valueOf`, which no expression can write. d3's nine alias tests,
 * `easeBounce === easeBounceOut`, could not be recorded (the recorder wraps each export, so no two
 * are the same function); [Ease]'s table is asserted against them directly instead.
 */
class UpstreamEaseVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val vectors: List<JsonObject> by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/d3-ease.json",
      )
    assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map { it.jsonObject }
  }

  /** A recorded argument as the value an expression would hand the function, or null for none. */
  private fun argument(element: JsonElement): VegaValue? =
    when (element) {
      is JsonPrimitive ->
        when {
          element.isString -> VegaValue.Str(element.content)
          element.doubleOrNull != null -> VegaValue.Num(element.doubleOrNull!!)
          else -> VegaValue.Null
        }
      is JsonObject ->
        when (element["\$"]?.jsonPrimitive?.content) {
          "-0" -> VegaValue.Num(-0.0)
          "NaN" -> VegaValue.Num(Double.NaN)
          "Infinity" -> VegaValue.Num(Double.POSITIVE_INFINITY)
          "-Infinity" -> VegaValue.Num(Double.NEGATIVE_INFINITY)
          else -> null
        }
      else -> null
    }

  private fun result(element: JsonElement?): Double? =
    when (element) {
      is JsonPrimitive -> element.doubleOrNull
      is JsonObject -> (argument(element) as? VegaValue.Num)?.value
      else -> null
    }

  @Test
  fun `d3-ease's own vectors replay against the ease functions`() {
    var replayed = 0
    val unmapped = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()
    for (vector in vectors) {
      val fn = vector["fn"]!!.jsonPrimitive.content
      val function = Functions.functions[fn]
      val expected = result(vector["result"])
      // Only the first argument is read: d3's poly tests pass a second one to show it is ignored.
      val t = vector["args"]!!.jsonArray.firstOrNull()?.let { argument(it) }
      if (function == null || expected == null || t == null) {
        unmapped.merge(fn, 1, Int::plus)
        continue
      }
      val actual = function.invoke(listOf(t)).asNumberOrNull()
      replayed++
      val close =
        actual != null &&
          (actual.equals(expected) ||
            (TRANSCENDENTAL.any { fn.startsWith(it) } &&
              abs(actual - expected) <= TRANSCENDENTAL_ULPS * expected.ulp))
      if (!close) failures.add("$fn($t): expected $expected, got $actual")
    }
    println("replayed $replayed of ${vectors.size} d3-ease vectors")
    unmapped.forEach { (fn, n) -> println("  unmapped $fn: $n") }
    assertEquals(emptyList<String>(), failures, "d3-ease disagrees with this implementation")
    assertTrue(replayed >= 360, "only $replayed vectors replayed; the harness must not shrink")
  }

  private companion object {
    /**
     * The families whose curves go through `pow`, `sin` or `cos`, and so through a platform libm.
     */
    val TRANSCENDENTAL = listOf("easePoly", "easeSin", "easeExp", "easeElastic")

    /**
     * How far a platform's rounding of those may land from V8's. One has been seen; four is slack.
     */
    const val TRANSCENDENTAL_ULPS = 4
  }

  @Test
  fun `each unsuffixed name is d3's alias`() {
    // d3-ease's `index.js`: the in-out form for most families, and the **out** form for the two
    // whose natural shape is a landing — `easeBounce` and `easeElastic`.
    val aliases =
      mapOf(
        "easeQuad" to "easeQuadInOut",
        "easeCubic" to "easeCubicInOut",
        "easePoly" to "easePolyInOut",
        "easeSin" to "easeSinInOut",
        "easeExp" to "easeExpInOut",
        "easeCircle" to "easeCircleInOut",
        "easeBounce" to "easeBounceOut",
        "easeBack" to "easeBackInOut",
        "easeElastic" to "easeElasticOut",
      )
    // Compared by what they answer rather than by identity: two references to one Kotlin function
    // are two objects. Every hundredth of the unit interval, and past both ends.
    for ((alias, target) in aliases) {
      val a = Ease.functions.getValue(alias)
      val b = Ease.functions.getValue(target)
      for (i in -10..110) {
        val t = i / 100.0
        assertEquals(b(t), a(t), "$alias is not $target at $t")
      }
    }
  }
}
