package dev.aster.vega.compose.mp

import java.io.File

/**
 * The golden files every host reads, and the one parser for them.
 *
 * Written once here and once in Swift, with the only job of being byte-identical — the arrangement
 * `test-fixtures/scene-walk` arrived at, for the reason recorded there: two recorders drift, so
 * what sits between them is a golden rather than each other.
 */
object HostConformance {

  const val FONT_STACK: String = "test-fixtures/host-conformance/font-stack.txt"

  const val IMAGE_RESOLVER: String = "test-fixtures/host-conformance/image-resolver.txt"

  const val PLACEMENT: String = "test-fixtures/host-conformance/placement.txt"

  val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

  /** `input -> a | b | c` as a pair, skipping comments and blank lines. */
  fun cases(golden: File): List<Pair<String, List<String>>> =
    golden
      .readLines()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .map { line ->
        val arrow = line.indexOf(" -> ")
        require(arrow > 0) { "not a case: $line" }
        val input = line.substring(0, arrow)
        val rest = line.substring(arrow + 4).trim()
        input to if (rest.isEmpty()) emptyList() else rest.split(" | ").map { it.trim() }
      }

  /** `a.png,b.png x3` as the urls and the number of frames. */
  fun repeatedCase(case: String): Pair<List<String>, Int> {
    val at = case.lastIndexOf(" x")
    require(at > 0) { "not a repeated case: $case" }
    return case.substring(0, at).split(",").map { it.trim() } to
      case.substring(at + 2).trim().toInt()
  }

  /** `200x100 in 400x400` as the scene's size and the slot's. */
  fun placementCase(raw: String): Pair<Pair<Double, Double>, Pair<Double, Double>> {
    val parts = raw.split(" in ")
    require(parts.size == 2) { "not a placement case: $raw" }
    fun size(text: String): Pair<Double, Double> {
      val wh = text.split("x").map { it.trim().toDouble() }
      require(wh.size == 2) { "not a size: $text" }
      return wh[0] to wh[1]
    }
    return size(parts[0]) to size(parts[1])
  }

  /** Six places, which every case in the goldens reaches exactly. */
  fun six(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)
}
