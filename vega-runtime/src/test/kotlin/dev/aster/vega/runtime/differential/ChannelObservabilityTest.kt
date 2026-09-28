package dev.aster.vega.runtime.differential

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every channel the corpus records can be made to **fail**.
 *
 * This is the test that would have caught, mechanically, the three blind spots found by hand: a
 * comparison reading `abs(wanted - got) > tolerance`, which is false whenever either side is `NaN`;
 * a loop walking only the reference's channels, so anything this engine invented was never looked
 * at; and a routing branch that swallowed the non-finite spellings back into the numbers. Each of
 * them left a channel that no disagreement could disturb, and every corpus went on passing.
 *
 * The property is the weakest one worth having and the only one that is cheap: for each channel the
 * references actually carry, there is **some** mark where changing this engine's value produces a
 * difference. It does not say the comparison is right — it says the comparison is connected.
 *
 * A channel is tried against several marks rather than one, because a guard may legitimately
 * silence it on a particular mark: a `strokeWidth` on something with no stroke paints nothing, and
 * `unpaintedStroke` says so. Silence everywhere is the failure; silence somewhere is a rule.
 */
class ChannelObservabilityTest {

  /**
   * The ways this test knows to say "not that value", **grouped by kind**, because a channel has to
   * survive each kind rather than any one of them.
   *
   * Asking only whether *some* perturbation is reported is too weak, and the first version of this
   * test proved it: reverting `agree` to a bare `abs(wanted - got) > tolerance` left every channel
   * "observable", because moving a number to another number is seen perfectly well by that test.
   * The hole was never about a channel being unreachable — it was about one **kind** of
   * disagreement being unreachable, and a `NaN` was the kind.
   *
   * A number therefore has two kinds, and both must be reported somewhere. The strings have one,
   * because their candidates are alternatives rather than requirements: appending a space to a
   * coordinate list is not a change — `pointsMatch` splits on whitespace and compares numerically —
   * and `"bold "` is the same font weight as `"bold"`. A comparison is *right* to ignore those.
   */
  private fun perturbations(
    mark: Differential.Mark,
    channel: String,
  ): Map<String, List<Differential.Mark>> {
    mark.numbers[channel]?.let { held ->
      val moved = if (held.isFinite()) held + 1234.5 else 1234.5
      return buildMap {
        put("moved", listOf(mark.copy(numbers = mark.numbers + (channel to moved))))
        // A `NaN` is the kind the comparison was blind to: `abs` of anything involving one is
        // `NaN`, and `NaN > tolerance` is false, so this engine could report one for any channel
        // and every corpus went on passing. Only meaningful where the reference holds a number.
        if (held.isFinite()) {
          put("NaN", listOf(mark.copy(numbers = mark.numbers + (channel to Double.NaN))))
        }
      }
    }
    val held = mark.strings[channel] ?: return emptyMap()
    val candidates = mutableListOf<String>()
    candidates += if (held == "#123456") "#654321" else "#123456"
    Regex("-?\\d+(\\.\\d+)?").find(held)?.let { first ->
      candidates += held.replaceRange(first.range, (first.value.toDouble() + 1234.5).toString())
    }
    candidates += "aster-perturbation"
    return mapOf(
      "text" to
        candidates.filter { it != held }.map { mark.copy(strings = mark.strings + (channel to it)) }
    )
  }

  private object SceneColorish {
    fun looksLikeColour(value: String): Boolean =
      value.startsWith("#") || value.startsWith("rgb") || value.startsWith("hsl")
  }

  @Test
  fun `every channel the corpus records can be made to fail`() {
    val byChannel = LinkedHashMap<String, MutableList<Differential.Mark>>()
    for (file in referenceFiles()) {
      for (mark in Differential.readReference(file).marks) {
        for (channel in mark.numbers.keys + mark.strings.keys) {
          val candidates = byChannel.getOrPut("${mark.type}.$channel") { mutableListOf() }
          if (candidates.size < CANDIDATES) candidates += mark
        }
      }
    }
    assertTrue(
      // **A floor, for the same reason `scripts/test-counts.py` floors the test counts.** The
      // assertion below says every channel is observable, and it says so vacuously if the corpus
      // stops recording them — a harvester that quietly dropped half its channels would pass. The
      // number only moves when the references change, and it should move **up**: 152 pairs of mark
      // type and channel, over 260 fixtures, on 2026-09-28.
      byChannel.size >= CHANNEL_FLOOR,
      "the references record ${byChannel.size} (markType, channel) pairs, below the floor of " +
        "$CHANNEL_FLOOR — either the corpus shrank or the harvester stopped recording something",
    )

    val blind = mutableListOf<String>()
    for ((key, marks) in byChannel) {
      val channel = key.substringAfterLast('.')
      val kinds = marks.flatMap { perturbations(it, channel).keys }.toSet()
      for (kind in kinds) {
        val reported = marks.any { mark ->
          perturbations(mark, channel)[kind].orEmpty().any { moved ->
            Differential.compareMarks(listOf(mark), listOf(moved)).any {
              it.where.endsWith(".$channel")
            }
          }
        }
        if (!reported) blind += "$key [$kind]"
      }
    }
    blind.sort()

    assertEquals(
      emptyList<String>(),
      blind.toList(),
      "no disagreement in these channels is reported by any mark that carries one",
    )
  }

  private fun referenceFiles(): List<File> =
    File(File(System.getProperty("user.dir")).parentFile, "test-fixtures/reference")
      .listFiles()
      .orEmpty()
      .filter { it.name.endsWith(".reference.json") }
      .sortedBy { it.name }

  private companion object {
    /** How many marks a channel is tried against before it counts as unreachable. */
    const val CANDIDATES = 12

    /** See the note beside the assertion; raise it when the corpus records more. */
    const val CHANNEL_FLOOR = 152
  }
}
