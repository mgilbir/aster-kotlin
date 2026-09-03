package dev.aster.vega.runtime.differential

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The container every mark is announced as, across the whole corpus, against upstream.
 *
 * Upstream draws each mark's items inside one element and hangs the mark's own announcement on it:
 * `role`, an `aria-roledescription` naming what kind of thing it is, and the `aria-label` a
 * mark-level `description` supplies. None of that is geometry, so the differential comparison is
 * blind to it — the same blind spot `zindex` hid in. The reference is harvested from the same
 * upstream renders the fixtures compare against, which makes it a wider net than any hand-written
 * set: 160-odd marks across the corpus, including the awkward ones nobody would think to write down
 * — a trellis's row headers, a group mark nested three deep, a text mark that is an object rather
 * than a symbol.
 *
 * Compared as a **multiset per fixture** rather than in order, for the same reason the captions
 * are: the two engines walk their scenes in different orders and what a reader meets first is
 * decided by the tree, not by the announcement.
 */
class MarkContainerTest {

  private val repositoryRoot = File(System.getProperty("user.dir")).parentFile

  private fun expectations(): List<Pair<String, String>> {
    val file = File(repositoryRoot, "test-fixtures/reference/mark-containers.json")
    require(file.isFile) { "Missing ${file.path}; regenerate it with ./scripts/oracle.sh" }
    return (VegaJson.parse(file.readText()) as VegaValue.Arr).values.map {
      val obj = it as VegaValue.Obj
      fun text(key: String) = obj.fields[key]?.takeIf { v -> v !is VegaValue.Null }?.asString()
      obj.fields["fixture"]!!.asString() to
        describe(
          kind = text("kind"),
          role = text("role"),
          roleDescription = text("roleDescription"),
          label = text("label"),
          hidden = (obj.fields["hidden"] as? VegaValue.Bool)?.value == true,
        )
    }
  }

  /** One announcement as a single comparable line, so a mismatch names which part disagrees. */
  private fun describe(
    kind: String?,
    role: String?,
    roleDescription: String?,
    label: String?,
    hidden: Boolean,
  ): String =
    if (hidden) "$kind hidden" else "$kind role=$role desc=$roleDescription label=${label ?: "-"}"

  /**
   * Our own containers for one fixture.
   *
   * Rebuilt from runs of items that agree on which mark they came from, which is how a renderer
   * finds them: this scene has no mark level of its own.
   */
  private fun containersOf(fixture: String): List<String> {
    val json = File(repositoryRoot, "test-fixtures/specs/$fixture.vg.json").readText()
    val loader = dev.aster.vega.loader.FileDataLoader(File(repositoryRoot, "test-fixtures"))
    val scene = SpecCompiler(loader = loader).compileJson(json).scene ?: return emptyList()
    val out = mutableListOf<String>()

    fun record(node: SceneNode) {
      val container = node.metadata.markAccessibility ?: return
      out +=
        describe(
          kind = node.metadata.markKind,
          role = container.role,
          roleDescription = container.roleDescription,
          label = container.label,
          hidden = container.hidden,
        )
    }

    fun walk(node: SceneNode) {
      if (node is GroupNode) {
        var index = 0
        val children = node.children
        while (index < children.size) {
          val first = children[index]
          if (first.metadata.markAccessibility == null) {
            walk(first)
            index++
            continue
          }
          record(first)
          var end = index
          while (end + 1 < children.size && sameMark(children[end + 1], first)) end++
          for (i in index..end) walk(children[i])
          index = end + 1
        }
      }
    }

    record(scene.root)
    walk(scene.root)
    return out
  }

  private fun sameMark(node: SceneNode, first: SceneNode): Boolean =
    node.metadata.markAccessibility == first.metadata.markAccessibility &&
      node.metadata.markOrdinal == first.metadata.markOrdinal &&
      node.metadata.markName == first.metadata.markName &&
      node.metadata.markKind == first.metadata.markKind

  @Test
  fun `every mark container matches upstream`() {
    val expected = expectations().groupBy({ it.first }, { it.second })
    val mismatches = mutableListOf<String>()
    var compared = 0

    for ((fixture, wanted) in expected) {
      val want = wanted.sorted()
      val got = containersOf(fixture).sorted()
      compared += want.size
      if (want != got) {
        mismatches +=
          "$fixture\n  upstream: ${want.joinToString("\n            ")}\n" +
            "  ours:     ${got.joinToString("\n            ")}"
      }
    }

    assertTrue(compared > 300, "expected the whole corpus, compared only $compared")
    assertEquals("", mismatches.joinToString("\n\n"))
  }

  /**
   * Every fixture in the corpus is **in** the reference, so a new one cannot be skipped in silence.
   *
   * The loop above walks the *reference* and asks this engine for a matching answer, so a fixture
   * the reference has never heard of is not compared — it is not reported either, because there is
   * nothing to disagree with. That is how two fixtures landed with no container announcements
   * checked at all: their references were built by running `oracle-js/src/reference.js` directly,
   * which writes the scene comparison and nothing else, where `scripts/oracle.sh` also harvests the
   * captions and the containers. The row floor above could not see it, counting rows rather than
   * fixtures: 2,234 is comfortably over 300 whether a fixture is missing or not.
   *
   * Every fixture produces at least one container — a chart has marks, and each mark is announced —
   * so absence means the harvest was not run rather than that there was nothing to say.
   * `guide-captions.json` gets no such check on purpose: a fixture with no axis, legend or title
   * genuinely has no caption, and 55 of them do not.
   */
  @Test
  fun `every fixture in the corpus was harvested`() {
    val corpus =
      File(repositoryRoot, "test-fixtures/specs")
        .listFiles { file -> file.name.endsWith(".vg.json") }
        .orEmpty()
        .map { it.name.removeSuffix(".vg.json") }
        .sorted()
    assertTrue(corpus.isNotEmpty(), "no fixtures found, so this test checks nothing")
    val harvested = expectations().map { it.first }.toSet()
    val missing = corpus.filterNot { it in harvested }
    assertEquals(
      emptyList<String>(),
      missing,
      "these fixtures are in the corpus and not in mark-containers.json, so their container " +
        "announcements are compared against nothing. Regenerate with ./scripts/oracle.sh",
    )
  }
}
