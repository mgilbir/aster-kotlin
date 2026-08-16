package dev.aster.vega.expression

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `String(x)` for a spread of doubles, replayed against [JsSemantics.numberToString].
 *
 * This is the conversion behind every number a chart writes as text — an axis label, a tooltip,
 * `datum.value + ''` in an expression — and it is the one piece of formatting that is never told
 * how many digits to write. JavaScript's answer is the *shortest* decimal no other double is nearer
 * to, and getting that wrong is not a rounding difference: it is a different number on the screen.
 *
 * Kotlin's own `toString` is not that function, on three counts, and this corpus found all three.
 * It switches to exponential notation at 10^7 where JavaScript switches at 10^21, so
 * `1777860673.6878662` printed as `1.7778606736878662e+9`. It writes at least two significant
 * digits, so `Double.MIN_VALUE` came out a digit longer than it needs to be. And the integer
 * shortcut in front of it saturated: `toLong()` stops at 9.2×10^18, so every integral double above
 * that printed as `-9223372036854775808`.
 *
 * Vectors are compared as **bits** rather than as text, so a double that the file and the test
 * disagree about parsing cannot quietly agree about printing.
 */
class UpstreamNumberStringVectorsTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val document: JsonObject by lazy {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/js-number-strings.json",
      )
    assumeTrue(
      file.isFile,
      "no vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    json.parseToJsonElement(file.readText()).jsonObject
  }

  @Test
  fun `javascript's number-to-string replays across the spread of doubles`() {
    val failures = mutableListOf<String>()
    var replayed = 0

    for (entry in document["numbers"]!!.jsonArray.map { it.jsonArray }) {
      val value = Double.fromBits(entry[0].bits())
      val expected = entry[1].jsonPrimitive.content
      replayed++
      val actual = JsSemantics.numberToString(value)
      if (expected != actual) failures.add("$expected -> ours $actual")
    }

    File(File(System.getProperty("user.dir")).parentFile, "build/js-number-strings-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(
          "replayed $replayed doubles\n" + failures.joinToString("\n") { "MISMATCH $it" } + "\n"
        )
      }

    assertEquals(
      emptyList<String>(),
      failures.take(12),
      "JavaScript and this engine print ${failures.size} of $replayed doubles differently",
    )
    assertTrue(replayed >= 5000, "only $replayed doubles replayed; the corpus must not shrink")
  }

  private fun kotlinx.serialization.json.JsonElement.bits(): Long =
    jsonPrimitive.content.toULong().toLong()
}
