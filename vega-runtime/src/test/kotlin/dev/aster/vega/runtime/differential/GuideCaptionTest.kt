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
 * What a screen reader is told about every axis and legend in the corpus, against upstream.
 *
 * These captions are **not** covered by the differential harness: they are `aria-label` attributes,
 * not geometry, and nothing in the comparison model carries them. So they get their own reference
 * file, harvested from the same upstream renders — 127 captions across 59 fixtures, which is a
 * wider net than any hand-written set would be and includes the awkward cases nobody thinks to
 * write: a domain long enough to be truncated, a log axis whose thousands are separated, a UTC
 * scale read as a full date.
 */
class GuideCaptionTest {

  private val repositoryRoot = File(System.getProperty("user.dir")).parentFile

  private data class Expected(val fixture: String, val kind: String, val caption: String)

  private fun expectations(): List<Expected> {
    val file = File(repositoryRoot, "test-fixtures/reference/guide-captions.json")
    require(file.isFile) { "Missing ${file.path}; regenerate it with ./scripts/oracle.sh" }
    return (VegaJson.parse(file.readText()) as VegaValue.Arr).values.map {
      val obj = it as VegaValue.Obj
      Expected(
        obj.fields["fixture"]!!.asString(),
        obj.fields["kind"]!!.asString(),
        obj.fields["caption"]!!.asString(),
      )
    }
  }

  /** Our own captions for one fixture, in the order the scene produces them. */
  private fun captionsOf(fixture: String): List<Pair<String, String>> {
    val json = File(repositoryRoot, "test-fixtures/specs/$fixture.vg.json").readText()
    val scene = SpecCompiler().compileJson(json).scene ?: return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    fun walk(node: SceneNode) {
      val role = node.metadata.role
      if (role == "axis" || role == "legend") {
        node.metadata.accessibility?.let { out += role to it.label }
      }
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(scene.root)
    return out
  }

  @Test
  fun `every axis and legend caption matches upstream`() {
    val expected = expectations().groupBy { it.fixture }
    val mismatches = mutableListOf<String>()
    var compared = 0

    for ((fixture, wanted) in expected) {
      val ours = captionsOf(fixture)
      // Compare as multisets per kind: the two engines emit guides in different orders, and the
      // order a screen reader meets them in is decided by the scene tree, not by the caption.
      for (kind in listOf("axis", "legend")) {
        val want = wanted.filter { it.kind == kind }.map { it.caption }.sorted()
        val got = ours.filter { it.first == kind }.map { it.second }.sorted()
        compared += want.size
        if (want != got) {
          mismatches +=
            "$fixture/$kind\n  upstream: ${want.joinToString("\n            ")}\n" +
              "  ours:     ${got.joinToString("\n            ")}"
        }
      }
    }

    assertTrue(compared > 100, "expected the whole corpus, compared only $compared")
    assertEquals("", mismatches.joinToString("\n\n"))
  }
}
