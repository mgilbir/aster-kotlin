package dev.aster.vega.model.spec

import dev.aster.vega.model.DiagnosticCodes
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * How many of upstream's `config` blocks this engine reads, asked of the parser.
 *
 * The `config` row says `Partial` and then lists blocks in prose. Until this, that list was the
 * only record — and the one thing this week established is that a prose list of what is supported
 * goes stale in the flattering direction's opposite: the row claimed `config.range`, `config.group`
 * and `config.projection` were "reported by name" when all three are honoured.
 *
 * **The inventory is upstream's own defaults**, not the schema — Vega's schema does not describe
 * `config` at all, so there is nothing there to read. `vega-parser/src/config.js` builds the
 * default configuration, and its top-level keys are exactly the blocks a specification may write.
 * Scraping a source file is cruder than reading a schema, so the count is guarded: a shape change
 * that made the scrape find nothing would otherwise report perfect coverage of nothing, which is
 * the failure this repository keeps meeting.
 *
 * **What it measures, exactly.** Whether the block *name* is recognised — which is precisely what
 * the row claimed was untrue of three of them. Whether every property inside a block is honoured is
 * `ConfigTest`'s question, and it answers that against upstream's own vectors by setting one
 * property at every level of the precedence chain and seeing which drew.
 */
class UpstreamConfigCoverageTest {

  private val defaults = File("../oracle-js/node_modules/vega-parser/src/config.js")

  /** The top-level keys of upstream's default configuration object. */
  private fun blocks(): List<String> {
    assumeTrue(defaults.exists(), "no pinned vega-parser; run scripts/check.sh or npm ci")
    val body = defaults.readText().substringAfter("return {")
    return Regex("""^ {4}(\w+):""", RegexOption.MULTILINE)
      .findAll(body)
      .map { it.groupValues[1] }
      .distinct()
      .sorted()
      .toList()
  }

  private fun reported(block: String): Boolean {
    val json =
      """
      {"width": 40, "height": 40, "padding": 0,
       "config": {"$block": {}},
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "marks": [{"type": "rect", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                       "width": {"value": 5}, "height": {"value": 5}}}}]}
      """
        .trimIndent()
    return SpecParser().parseJson(json).diagnostics.any {
      it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY && "'$block'" in it.message
    }
  }

  @Test
  fun `every config block upstream defaults is read or reported`() {
    val all = blocks()
    // The guard on the scrape: upstream ships twenty-five, and a regex that stopped matching would
    // otherwise report every block covered.
    assertTrue(
      all.size >= 20,
      "only ${all.size} config blocks scraped from upstream's defaults; the file's shape changed " +
        "and this test is now measuring nothing — $all",
    )

    val unread = all.filter { reported(it) }
    File("build/config-coverage.json")
      .apply { parentFile.mkdirs() }
      .writeText(
        """{"kind": "config", "upstream": ${all.size}, "read": ${all.size - unread.size}, """ +
          """"reported": [${unread.joinToString(", ") { "\"$it\"" }}]}""" +
          "\n"
      )

    assertTrue(
      unread.size <= FLOOR,
      "${unread.size} config blocks are reported as unimplemented, expected at most $FLOOR — $unread",
    )
  }

  /**
   * A block upstream has never had is reported, so the probe detects something.
   *
   * The guard on the guard. Every coverage probe written in this repository has needed one, and two
   * of the three have caught a probe measuring its own input rather than the engine.
   */
  @Test
  fun `a config block nobody has heard of is reported`() {
    blocks()
    assertTrue(
      reported("aBlockUpstreamHasNeverHad"),
      "an invented config block drew no diagnostic, so this probe detects nothing",
    )
  }

  private companion object {
    /**
     * **None** may be reported, which is what the measurement says: all 25 are read.
     *
     * A ceiling a regression trips, not a target. A version bump that adds a block this engine does
     * not read fails here rather than being absorbed into the word "partial".
     */
    const val FLOOR = 0
  }
}
