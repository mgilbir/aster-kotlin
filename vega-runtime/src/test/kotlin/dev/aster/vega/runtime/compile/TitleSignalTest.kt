package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Any of a title's properties may be written as a **signal**.
 *
 * ```js
 * addEncoders(encode, {
 *   text:       text,
 *   …
 *   fill:       _('color'),
 *   font:       _('font'),
 *   fontStyle:  _('fontStyle'),
 *   fontWeight: _('fontWeight'),
 * }, { align: _('align'), baseline: _('baseline') });
 * ```
 * ```js
 * if (spec.subtitle) {
 *   children.push(buildSubTitle(spec, _, encode.subtitle, dataRef));
 * }
 * ```
 *
 * `parseTitle` does not read a title's properties as values at all: it turns every one of them into
 * an **encoder** on the text mark it belongs to. That is the whole reason any of them may be a
 * signal — an encode channel takes one wherever it appears — and it is why `if (spec.subtitle)`
 * builds a subtitle for `{"signal": …}` as readily as for a word, the reference being an object and
 * objects being truthy.
 *
 * This engine read the *string* properties as strings, so a signal parsed to nothing: the colour
 * fell back to the default and a subtitle written as a signal produced no mark at all. The numeric
 * properties already carried a signal of their own. A chart whose heading comes from a parameter —
 * which is how a templated dashboard writes one — lost its subtitle entirely and was drawn in
 * black. Sixty-one of the sixty-three Deneb templates name their title that way.
 *
 * Every expectation was rendered with upstream Vega rather than reasoned about.
 */
class TitleSignalTest {

  /** The heading's marks, in the order they are drawn. */
  private fun titles(title: String, signals: String = ""): List<TextNode> {
    val declared = if (signals.isEmpty()) "" else """"signals":[$signals],"""
    val spec =
      """{"width":200,"height":100,"padding":0,"autosize":"none",$declared
          "title":$title,
          "scales":[{"name":"y","type":"linear","domain":[0,10],"range":"height"}],
          "axes":[{"orient":"left","scale":"y"}]}"""
    val scene = requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec).scene)
    return scene
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role?.startsWith("title-") == true }
  }

  /** Each part of the heading, with what it is set in. */
  private fun heading(title: String, signals: String = ""): String =
    titles(title, signals).joinToString(" | ") { node ->
      val style = node.layout.run.style
      val colour = (node.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex()
      "${node.metadata.role} fill=$colour text=${node.text} font=${style.fontFamily} " +
        "weight=${style.fontWeight} style=${style.fontStyle}"
    }

  /** Literal properties, which is what already worked and must not move. */
  @Test
  fun `a title written in words is unchanged`() {
    assertEquals(
      "title-text fill=#333333 text=T font=sans-serif weight=700 style=NORMAL | " +
        "title-subtitle fill=#000000 text=S font=sans-serif weight=400 style=NORMAL",
      heading("""{"text":"T","color":"#333333","subtitle":"S"}"""),
    )
  }

  /** The reported shape: the colour and the subtitle both come from parameters. */
  @Test
  fun `a colour and a subtitle given as signals are read`() {
    assertEquals(
      "title-text fill=#333333 text=T font=sans-serif weight=700 style=NORMAL | " +
        "title-subtitle fill=#000000 text=S font=sans-serif weight=400 style=NORMAL",
      heading(
        """{"text":"T","color":{"signal":"c"},"subtitle":{"signal":"s"}}""",
        signals = """{"name":"c","value":"#333333"},{"name":"s","value":"S"}""",
      ),
    )
  }

  /** The subtitle's own colour and face, which are separate properties from the title's. */
  @Test
  fun `a subtitle's colour and face may be signals`() {
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL | " +
        "title-subtitle fill=#aa0000 text=S font=serif weight=400 style=NORMAL",
      heading(
        """{"text":"T","subtitle":"S","subtitleColor":{"signal":"c"},
            "subtitleFont":{"signal":"f"}}""",
        signals = """{"name":"c","value":"#aa0000"},{"name":"f","value":"serif"}""",
      ),
    )
  }

  /**
   * A weight and a style too, and they are **measured** as well as drawn: a heading set normal
   * rather than bold is narrower, so this is the arm that would move a chart's size if it were
   * wrong.
   */
  @Test
  fun `a weight and a style given as signals are read`() {
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=400 style=ITALIC",
      heading(
        """{"text":"T","fontWeight":{"signal":"w"},"fontStyle":{"signal":"y"}}""",
        signals = """{"name":"w","value":"normal"},{"name":"y","value":"italic"}""",
      ),
    )
  }

  /** The face of the heading itself, which is a different property from the subtitle's. */
  @Test
  fun `a title's font may be a signal`() {
    assertEquals(
      "title-text fill=#000000 text=T font=serif weight=700 style=NORMAL",
      heading(
        """{"text":"T","font":{"signal":"f"}}""",
        signals = """{"name":"f","value":"serif"}""",
      ),
    )
  }

  /** The subtitle's own weight and style, set bold and italic where the title's are not. */
  @Test
  fun `a subtitle's weight and style may be signals`() {
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL | " +
        "title-subtitle fill=#000000 text=S font=sans-serif weight=700 style=ITALIC",
      heading(
        """{"text":"T","subtitle":"S","subtitleFontWeight":{"signal":"w"},
            "subtitleFontStyle":{"signal":"y"}}""",
        signals = """{"name":"w","value":"bold"},{"name":"y","value":"italic"}""",
      ),
    )
  }

  /**
   * `align` and `baseline` too, which are where the words are put rather than how they look — a
   * heading anchored right and drawn from its bottom edge occupies a different box.
   */
  @Test
  fun `an align and a baseline given as signals are read`() {
    val title =
      titles(
          """{"text":"T","align":{"signal":"a"},"baseline":{"signal":"b"}}""",
          signals = """{"name":"a","value":"right"},{"name":"b","value":"bottom"}""",
        )
        .single()
    assertEquals(TextAlign.RIGHT, title.layout.run.align)
    assertEquals(TextBaseline.BOTTOM, title.layout.run.baseline)
  }

  /**
   * A subtitle whose signal resolves to nothing is still a subtitle.
   *
   * `if (spec.subtitle)` tests the property, and a signal reference is an object — truthy however
   * it evaluates. So this draws an empty subtitle where an empty *literal* draws none, and the pair
   * is the reason presence is read off the specification rather than off the words.
   */
  @Test
  fun `a subtitle signal that resolves to nothing still draws one`() {
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL | " +
        "title-subtitle fill=#000000 text= font=sans-serif weight=400 style=NORMAL",
      heading(
        """{"text":"T","subtitle":{"signal":"s"}}""",
        signals = """{"name":"s","value":""}""",
      ),
    )
  }

  /** The other half of that pair: an empty literal is no subtitle at all. */
  @Test
  fun `an empty subtitle written in words draws none`() {
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL",
      heading("""{"text":"T","subtitle":""}"""),
    )
  }

  /**
   * And an `encode` block is not a subtitle either: it styles one the property asked for, and
   * upstream never reaches `buildSubTitle` without that property — not even when the block says
   * what the words would have been.
   */
  @Test
  fun `an encode block alone is not a subtitle`() {
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL",
      heading("""{"text":"T","encode":{"subtitle":{"update":{"text":{"value":"S"}}}}}"""),
    )
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL",
      heading(
        """{"text":"T","subtitle":"",
            "encode":{"subtitle":{"update":{"text":{"value":"S"}}}}}"""
      ),
    )
  }

  /**
   * A signal with no expression in it costs nothing.
   *
   * Upstream evaluates `{"signal": ""}` to nothing and draws the heading unpainted. Folding an
   * empty expression in here would instead put it through the expression parser, which reports an
   * error and takes the whole chart down with it — so the property is left to the field and the
   * words keep the default colour. The words being drawn at all is the part that matters.
   */
  @Test
  fun `a signal with no expression in it still draws the chart`() {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """{"width":200,"height":100,"padding":0,"autosize":"none",
              "title":{"text":"T","color":{"signal":""}},
              "scales":[{"name":"y","type":"linear","domain":[0,10],"range":"height"}],
              "axes":[{"orient":"left","scale":"y"}]}"""
        )
    assertEquals(
      emptyList<String>(),
      compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }.map { it.message },
    )
    assertEquals(
      "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL",
      heading("""{"text":"T","color":{"signal":""}}"""),
    )
  }

  /**
   * An `enter` block, though, **loses** to a property written as a signal.
   *
   * `addEncode` sends a signal to `update` however it was written, and `extendEncode` merges the
   * specification's own blocks one for one — so its `enter` never meets the encoder it meant to
   * override. The literal case below is the same rule read the other way: a literal goes to
   * `enter`, where the specification's `enter` does reach it.
   */
  @Test
  fun `a signal property beats an enter block, where a literal one loses to it`() {
    assertEquals(
      "title-text fill=#333333 text=T font=sans-serif weight=700 style=NORMAL",
      heading(
        """{"text":"T","color":{"signal":"c"},
            "encode":{"title":{"enter":{"fill":{"value":"#00ff00"}}}}}""",
        signals = """{"name":"c","value":"#333333"}""",
      ),
    )
    assertEquals(
      "title-text fill=#00ff00 text=T font=sans-serif weight=700 style=NORMAL",
      heading(
        """{"text":"T","color":"#333333",
            "encode":{"title":{"enter":{"fill":{"value":"#00ff00"}}}}}"""
      ),
    )
  }

  /**
   * A subtitle the specification asked for takes its words from the block, in either set: the
   * property put a literal in `enter`, and the specification's own `enter` overrides it there while
   * its `update` overrides it from the set above.
   */
  @Test
  fun `an encode block supplies the words of a subtitle the property asked for`() {
    for (set in listOf("enter", "update")) {
      assertEquals(
        "title-text fill=#000000 text=T font=sans-serif weight=700 style=NORMAL | " +
          "title-subtitle fill=#000000 text=E font=sans-serif weight=400 style=NORMAL",
        heading(
          """{"text":"T","subtitle":"S",
              "encode":{"subtitle":{"$set":{"text":{"value":"E"}}}}}"""
        ),
        "the $set block should have supplied the subtitle's words",
      )
    }
  }

  /**
   * The specification's own `update` still wins, which is the order `guideMark` merges them in: the
   * encoders built from the properties first, the block the specification wrote over the top.
   */
  @Test
  fun `an encode block beats the property it duplicates`() {
    assertEquals(
      "title-text fill=#00ff00 text=T font=sans-serif weight=700 style=NORMAL",
      heading(
        """{"text":"T","color":{"signal":"c"},
            "encode":{"title":{"update":{"fill":{"value":"#00ff00"}}}}}""",
        signals = """{"name":"c","value":"#333333"}""",
      ),
    )
  }
}
