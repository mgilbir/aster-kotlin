package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalBind
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The accessors a **foreign host** reads and writes a chart's controls through.
 *
 * These exist because every interesting [VegaValue] is a `@JvmInline value class` and therefore has
 * no Obj-C representation: from Swift a signal's value could neither be read nor *made*, so a host
 * could draw a slider and had no way to say where the reader put it. The Swift suite proves they
 * cross that boundary; this one proves they say the right thing, and runs in `scripts/check.sh`
 * where the Swift tests do not.
 */
class ForeignSignalsTest {

  private val json =
    """
    {
      "${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
      "width": 100, "height": 50, "padding": 0,
      "signals": [
        {"name": "size", "value": 2,
         "bind": {"input": "range", "min": 1, "max": 9, "step": 2}},
        {"name": "shade", "value": "steelblue",
         "bind": {"input": "radio", "options": ["steelblue", "firebrick"], "labels": ["blue", ""]}},
        {"name": "quiet", "value": true, "bind": {"input": "checkbox"}},
        {"name": "unbound", "value": 7}
      ],
      "marks": []
    }
    """

  private fun compiled() =
    SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(json)

  @Test
  fun `a value survives being made and read again`() {
    assertEquals(42.5, ForeignSignals.number(ForeignSignals.ofNumber(42.5)))
    assertEquals(true, ForeignSignals.boolean(ForeignSignals.ofBoolean(true)))
    assertEquals("firebrick", ForeignSignals.text(ForeignSignals.ofString("firebrick")))

    // A timestamp answers its epoch millis as a number, because a host drawing a slider over a date
    // has nothing else to put on the axis.
    assertEquals(
      1_767_225_600_000.0,
      ForeignSignals.number(VegaValue.Timestamp(1_767_225_600_000.0)),
    )

    // And a value is not quietly coerced across kinds: that is what keeps a checkbox from claiming
    // a
    // slider's value.
    assertNull(ForeignSignals.boolean(ForeignSignals.ofNumber(1.0)))
    assertNull(ForeignSignals.number(ForeignSignals.ofString("1")))
    assertEquals("null", ForeignSignals.kind(null))
    assertEquals("number", ForeignSignals.kind(ForeignSignals.ofNumber(1.0)))
  }

  @Test
  fun `the controls a chart asks for are the bound signals, in order`() {
    val inputs = ForeignSignals.inputs(compiled())

    // `unbound` is a signal and not a control, which is true of most signals in most charts.
    assertEquals(listOf("size", "shade", "quiet"), inputs.map { it.signal })
    assertEquals(
      listOf("range", "choice", "checkbox"),
      inputs.map { ForeignSignals.bindKind(it.bind) },
    )

    assertEquals(listOf(1.0, 9.0, 2.0), ForeignSignals.rangeBounds(inputs[0].bind))
    assertEquals(2.0, ForeignSignals.number(inputs[0].value))
    assertTrue(ForeignSignals.isRadio(inputs[1].bind), "`radio` asks for radio buttons")
    assertEquals(true, ForeignSignals.boolean(inputs[2].value))
  }

  @Test
  fun `a missing or empty choice label falls back to the option itself`() {
    val inputs = ForeignSignals.inputs(compiled())
    // The specification labels the first option and leaves the second empty, which upstream reads
    // as
    // `labels[i] || options[i]` — so the fallback is the option's own text rather than a blank row.
    assertEquals(listOf("blue", "firebrick"), ForeignSignals.choiceLabels(inputs[1].bind))
  }

  @Test
  fun `a chart with no bindings asks for no controls`() {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader)
        .compileJson(
          """
          {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
           "width": 10, "height": 10, "padding": 0, "marks": []}
          """
        )
    assertTrue(ForeignSignals.inputs(compiled).isEmpty())
  }

  @Test
  fun `asking a range question of another control answers nothing`() {
    // A host switches on `bindKind` and then asks the matching question; asking the wrong one gets
    // null rather than a plausible default, so a mistake shows up as a missing control.
    assertNull(ForeignSignals.rangeBounds(SignalBind.Checkbox()))
    assertNull(ForeignSignals.choiceOptions(SignalBind.Checkbox()))
    assertNull(ForeignSignals.choiceLabels(SignalBind.Range()))
  }
}
