package dev.aster.vega.model

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Every field the recorder writes is one an adapter has been told about.
 *
 * This exists because of a specific mistake, made twice. A recorded vector carries more than `args`
 * and `result`: `method` says which method of a returned object was called, and `chain` says how a
 * builder was configured before it was asked. An adapter that reads neither will happily compare
 * `rotation.invert(p)` against its own `rotation(p)`, or measure a default graticule against one
 * configured with `.step([45, 45])` — and the disagreement looks exactly like an engine bug. Both
 * happened; the second cost a wrong claim that the recorder could not capture builder chains, when
 * it had been capturing them all along.
 *
 * What this can check is narrow but real: if the recorder starts emitting a field nobody has
 * considered, the build fails here rather than in whichever adapter silently ignores it. What it
 * cannot check is an adapter ignoring a field that already exists — for that, the rule is the one
 * the rotation vector taught: **a structural disagreement is an adapter bug until proven
 * otherwise.** A hundred degrees of rotation is not a rounding difference, and no engine gets a
 * formula that wrong while getting the other eight vectors exactly right.
 */
class UpstreamVectorShapeTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `recorded vectors carry only the fields adapters know about`() {
    val directory =
      File(File(System.getProperty("user.dir")).parentFile, "test-fixtures/upstream-vectors")
    assumeTrue(
      directory.isDirectory,
      "no upstream vectors — run scripts/record-upstream-vectors.sh to check them",
    )
    val files =
      directory.listFiles().orEmpty().filter {
        it.extension == "json" && it.name !in NOT_RECORDINGS
      }
    assumeTrue(files.isNotEmpty(), "no recorded packages to check")

    val seen = sortedSetOf<String>()
    for (file in files) {
      val calls =
        json.parseToJsonElement(file.readText()).jsonObject["calls"]?.jsonArray ?: continue
      for (call in calls) seen.addAll(call.jsonObject.keys)
    }

    assertEquals(
      KNOWN.toSortedSet(),
      seen,
      "the recorder's vector shape changed; every adapter in Upstream*VectorsTest has to be " +
        "checked against the difference before this list is updated",
    )
  }

  /**
   * Every package an adapter reads is present, or none is.
   *
   * The replays each begin with an `assumeTrue` on their own vector file, because the corpus is
   * regenerated rather than committed and a fresh clone legitimately has none. That is right for an
   * empty directory and dangerous for a **half-full** one: a recording run that dies partway leaves
   * some packages behind, and every adapter whose file is missing then skips in silence while the
   * build stays green.
   *
   * This is not hypothetical. A `set -e` fault in the recording script once killed the run before
   * the entire d3 section, and what noticed was a person reading the ledgers. This notices instead.
   */
  @Test
  fun `a corpus is either complete or absent`() {
    val directory =
      File(File(System.getProperty("user.dir")).parentFile, "test-fixtures/upstream-vectors")
    assumeTrue(directory.isDirectory, "no upstream vectors — nothing to check")
    val present = directory.listFiles().orEmpty().map { it.name }.toSet()
    val recordings = present.filterNot { it in NOT_RECORDINGS }
    assumeTrue(recordings.isNotEmpty(), "no recorded packages — a fresh clone, which is fine")

    assertEquals(
      emptyList<String>(),
      REPLAYED_PACKAGES.filterNot { it in present }.sorted(),
      "the corpus is incomplete: these packages have an adapter but no recording, so those " +
        "replays are skipping silently. Re-run scripts/record-upstream-vectors.sh",
    )
  }

  private companion object {

    /**
     * The vector files an `Upstream*VectorsTest` reads. Kept beside the shape check because the two
     * answer the same question from different sides: that what the recorder wrote is what the
     * adapters expect.
     */
    val REPLAYED_PACKAGES =
      listOf(
        "d3-array.json",
        "d3-color.json",
        "d3-dsv.json",
        "d3-format.json",
        "d3-geo.json",
        "d3-interpolate.json",
        "d3-scale.json",
        "d3-time-format.json",
        "d3-time.json",
        "js-number-strings.json",
        "vega-scenegraph.json",
        "vega-statistics.json",
        "vega-time.json",
      )

    /** Files in the directory that are not recordings of a package's tests. */
    val NOT_RECORDINGS = setOf("known-divergences.json", "js-number-strings.json")

    /**
     * Every field a vector may carry.
     * - `package`, `fn`, `args`, `result`, `threw` — the call and what it answered.
     * - `constructedWith` — the arguments the thing was *built* from, for `format(".2f")(42)`.
     * - `chain` — the configuring calls made before the question, `[["step", [[45, 45]]]]`.
     * - `method` — which method of the built object was called, as against calling it directly.
     * - `op`, `params`, `input`, `output`, `instance`, `sequence` — a dataflow operator's
     *   `transform(_, pulse)`, its parameters, and the stamps that keep a stateful operator's calls
     *   in order.
     * - `value` — a plain exported value rather than a call.
     * - `oversized` — the answer was past the per-vector size budget and was dropped.
     */
    val KNOWN =
      setOf(
        "args",
        "chain",
        "constructedWith",
        "fn",
        "input",
        "instance",
        "method",
        "op",
        "output",
        "oversized",
        "package",
        "params",
        "result",
        "sequence",
        "threw",
        "value",
      )
  }
}
