package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalUpdate
import dev.aster.vega.model.spec.SpecParser
import dev.aster.vega.runtime.compile.ItemEncode
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.ScenePaint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tap goes in and a different scene comes out.
 *
 * The whole interaction chain end to end: parse the handlers, match an event to them, evaluate the
 * update, and recompile with the new signal pinned. Recompiling rather than recomputing
 * incrementally is a measured decision, not a shortcut — see STATUS.md's performance note.
 */
class SignalUpdateTest {

  private val json =
    """
    {
      "width": 200, "height": 100, "padding": 5,
      "data": [{"name": "t", "values": [
        {"c": "a", "v": 3}, {"c": "b", "v": 7}, {"c": "c", "v": 5}
      ]}],
      "signals": [
        {"name": "picked", "value": null,
         "on": [{"events": "rect:click", "update": "datum.c"}]},
        {"name": "clicks", "value": 0,
         "on": [{"events": "click", "update": "clicks + 1"}]}
      ],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0.1},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
         "range": "height", "zero": true}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"},
          "y2": {"scale": "y", "value": 0},
          "fill": {"signal": "datum.c === picked ? '#e45756' : '#4c78a8'"}
        }}
      }]
    }
    """
      .trimIndent()

  private val diagnostics = DiagnosticCollector()
  private val expressions = CachingExpressionCompiler(VegaExpressionCompiler())
  private val updater = SignalUpdater(expressions, diagnostics)

  private val spec = SpecParser().parseJson(json).spec!!

  private fun compile() = SpecCompiler().compile(spec, updater.overrides)

  private fun dispatcher(): EventDispatcher {
    val bindings =
      spec.signals.flatMap { signal ->
        signal.on.map { HandlerBinding(signal.name, it) }
      }
    return EventDispatcher(bindings, expressions, diagnostics, compile().signals)
  }

  /** Every rect's fill, so a selection is visible as one of them changing. */
  private fun fills(): List<Fill?> {
    val out = mutableListOf<Fill?>()
    fun walk(node: SceneNode) {
      when (node) {
        is RectNode -> out += node.fill
        is GroupNode -> node.children.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(compile().scene!!.root)
    return out
  }

  private val selected: Fill = Fill(ScenePaint.Solid(SceneColor.parse("#e45756")!!))

  @Test
  fun `clicking a bar selects it and the scene comes back different`() {
    val d = dispatcher()
    val before = fills()

    val fired = d.dispatch(InputEvent("click", 0, markType = "rect", datum = datumFor("b")))
    // Both handlers match: the mark one and the view-level one behind it.
    assertEquals(listOf("picked", "clicks"), fired.map { it.signalName })

    val changed = updater.apply(fired, compile().signals)
    assertEquals(setOf("picked", "clicks"), changed)
    assertEquals(VegaValue.Str("b"), updater.overrides["picked"])
    assertEquals(VegaValue.Num(1.0), updater.overrides["clicks"])

    val after = fills()
    assertNotEquals(before, after)
    // Exactly one bar turned red, and it is the middle one.
    assertEquals(listOf(false, true, false), after.map { it == selected }, after.toString())
  }

  /** The update reads the signal's own previous value, which is how a counter is written. */
  @Test
  fun `a handler can read the signal it is setting`() {
    val d = dispatcher()
    repeat(3) { tick ->
      val fired = d.dispatch(InputEvent("click", tick.toLong()))
      updater.apply(fired, compile().signals)
    }
    assertEquals(VegaValue.Num(3.0), updater.overrides["clicks"])
  }

  /**
   * A handler that produces the value the signal already has reports no change, so the caller can
   * skip the recompile. `force` is how a specification says to recompile anyway.
   */
  @Test
  fun `an unchanged value reports nothing to redo`() {
    val d = dispatcher()
    val first = d.dispatch(InputEvent("click", 0, markType = "rect", datum = datumFor("b")))
    updater.apply(first, compile().signals)

    val again = d.dispatch(InputEvent("click", 1, markType = "rect", datum = datumFor("b")))
    val changed = updater.apply(again, compile().signals)
    // `picked` is still "b" and reports nothing; `clicks` went 1 to 2 and reports itself.
    assertEquals(setOf("clicks"), changed)
  }

  /** A pinned signal survives the recompile; without that the handler's value would be lost. */
  @Test
  fun `an overridden signal keeps its value through the recompile`() {
    updater.apply(
      listOf(
        FiredHandler(
          "picked",
          spec.signals.first { it.name == "picked" }.on.first(),
          InputEvent("click", 0, markType = "rect", datum = datumFor("c")),
        )
      ),
      compile().signals,
    )
    assertEquals(VegaValue.Str("c"), compile().signals["picked"])
  }

  /**
   * A signal with both an `update` and a handler keeps the handler's value and does not re-run the
   * expression. Upstream would re-run it when one of its dependencies moved; nothing here tracks
   * that, so it is reported rather than left to be discovered.
   */
  @Test
  fun `a pinned signal that also has an update expression is reported`() {
    val withUpdate =
      SpecParser()
        .parseJson(
          json.replace(
            """{"name": "clicks", "value": 0,""",
            """{"name": "clicks", "value": 0, "update": "0",""",
          )
        )
        .spec!!
    val compiled = SpecCompiler().compile(withUpdate, mapOf("clicks" to VegaValue.Num(7.0)))
    assertEquals(VegaValue.Num(7.0), compiled.signals["clicks"])
    assertTrue(
      compiled.diagnostics.any { it.message.contains("will not run again") },
      compiled.diagnostics.toString(),
    )
  }

  /**
   * `encode` is upstream's own shorthand for an `encode(item(), ...)` call, and arrives as one.
   *
   * The parser rewrites it exactly as upstream's does, so there is one code path for both spellings
   * and the side effect is recorded here rather than in a special case: the item the event landed
   * on is noted with the block to overlay on it, ready for the next compile. It used to be reported
   * and dropped, on the grounds that the change belonged to a scene node this class does not own —
   * which was true and led nowhere, because the *record* of it belongs exactly here, beside the
   * signal values.
   */
  @Test
  fun `an encode handler records the item to overlay`() {
    val encodeSpec =
      SpecParser()
        .parseJson(
          json.replace(
            """"on": [{"events": "rect:click", "update": "datum.c"}]""",
            """"on": [{"events": "rect:click", "encode": "chosen"}]""",
          )
        )
        .spec!!
    val handler = encodeSpec.signals.first { it.name == "picked" }.on.first()
    assertEquals(
      SignalUpdate.Expression("encode(item(), 'chosen')"),
      handler.update,
      "the parser should desugar `encode` the way upstream's does",
    )
    val id = SceneNodeId(41)
    updater.apply(
      listOf(
        FiredHandler("picked", handler, InputEvent("click", 0, itemId = id, markType = "rect"))
      ),
      compile().signals,
    )
    assertEquals(mapOf(id to ItemEncode("chosen", fresh = true)), updater.itemEncodes)
    // Fresh until the compile that applies it has happened, and then not: the block beats the
    // mark's
    // `update` on the pass that applies it and loses to it on every pass after.
    updater.ageItemEncodes()
    assertEquals(mapOf(id to ItemEncode("chosen", fresh = false)), updater.itemEncodes)
    assertTrue(diagnostics.diagnostics.isEmpty(), diagnostics.diagnostics.toString())
  }

  private fun datumFor(category: String): VegaValue =
    VegaValue.Obj(
      mapOf(
        "c" to VegaValue.Str(category),
        "v" to VegaValue.Num(if (category == "b") 7.0 else 5.0),
      )
    )
}
