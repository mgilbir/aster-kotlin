package dev.aster.vega.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.SignalBind
import dev.aster.vega.runtime.SignalInput
import dev.aster.vega.runtime.VegaChartController
import kotlin.math.roundToInt

/**
 * The controls a specification's `bind` blocks ask for, drawn with Material widgets.
 *
 * Deliberately in the **demo** rather than in `vega-compose`. The engine's half of a binding is the
 * description and the write path — `controller.inputs` and `controller.setSignal` — and the widget
 * set is a host's taste: Material 3 here, something else on another platform, a row of buttons in a
 * kiosk. Putting Material in the library would make that choice for every host, and there is
 * nothing in this file a host could not write differently in an afternoon.
 *
 * What it does show is that the seam is enough. Every control below reads the signal's current
 * value out of `inputs` and writes the reader's back through `setSignal`; nothing reaches into the
 * scene, nothing knows what a scale is, and a signal changed by a *tap* moves these controls
 * because the list is republished on every compile.
 */
@Composable
internal fun SignalControls(controller: VegaChartController, modifier: Modifier = Modifier) {
  val inputs by controller.inputs.collectAsState()
  if (inputs.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    for (input in inputs) {
      when (val bind = input.bind) {
        is SignalBind.Range -> RangeControl(controller, input, bind)
        is SignalBind.Checkbox -> CheckboxControl(controller, input)
        is SignalBind.Choice -> ChoiceControl(controller, input, bind)
        is SignalBind.Field -> FieldControl(controller, input, bind)
      }
    }
  }
}

@Composable
private fun RangeControl(
  controller: VegaChartController,
  input: SignalInput,
  bind: SignalBind.Range,
) {
  val min = (bind.min ?: 0.0).toFloat()
  val max = (bind.max ?: 100.0).toFloat()
  val current = input.value.asDouble().let { if (it.isNaN()) min.toDouble() else it }.toFloat()
  // A step of zero means a continuous slider; Material counts the steps *between* the ends, which
  // is
  // one fewer than the number of positions.
  val steps =
    bind.step?.takeIf { it > 0.0 }?.let { (((max - min) / it).roundToInt() - 1).coerceAtLeast(0) }
      ?: 0
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Label(input.label)
    Slider(
      value = current.coerceIn(min, max),
      onValueChange = { controller.setSignal(input.signal, VegaValue.Num(it.toDouble())) },
      valueRange = min..max,
      steps = steps,
      modifier = Modifier.weight(1f).semantics { contentDescription = input.label },
    )
    // The number beside the handle, as upstream's range binding shows it.
    Text(
      text = input.value.asString(),
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.width(44.dp).padding(start = 4.dp),
    )
  }
}

@Composable
private fun CheckboxControl(controller: VegaChartController, input: SignalInput) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Label(input.label)
    Checkbox(
      checked = input.value.asBoolean(),
      onCheckedChange = { controller.setSignal(input.signal, VegaValue.Bool(it)) },
      modifier = Modifier.semantics { contentDescription = input.label },
    )
  }
}

/**
 * A `select` and a `radio` differ only in how they are drawn, and at this size they need not.
 *
 * Both are a row of chips: every option visible, the current one marked. A drop-down would hide the
 * choice behind a tap for no gain on a handful of options, which is what a specification's option
 * list almost always is.
 */
@Composable
private fun ChoiceControl(
  controller: VegaChartController,
  input: SignalInput,
  bind: SignalBind.Choice,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Label(input.label)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      bind.options.forEachIndexed { index, option ->
        FilterChip(
          selected = index == input.selectedIndex,
          onClick = { controller.setSignal(input.signal, option) },
          label = { Text(bind.labelAt(index), style = MaterialTheme.typography.labelSmall) },
        )
      }
    }
  }
}

/**
 * Every other input type, as a text field.
 *
 * `number` is written back as a number and everything else as text, because that is what the signal
 * is expected to hold: a specification computing `size * 2` from a `number` field needs a number,
 * and a `color` field's `#4c78a8` is a string wherever it goes. An input type this host has no
 * widget for lands here too, which is what a browser does with one it does not recognise.
 *
 * `placeholder` is the one carried attribute this host has somewhere to put — the hint an empty
 * field shows, which on `job-voyager` is the difference between a blank box and one that says what
 * to type in it. The others are used or ignored by the same rule: `autocomplete` is deliberately
 * ignored, because it asks a browser to offer a value it has saved from a *form* and there is no
 * form here, and ignoring it costs the reader nothing.
 */
@Composable
private fun FieldControl(
  controller: VegaChartController,
  input: SignalInput,
  bind: SignalBind.Field,
) {
  OutlinedTextField(
    value = input.value.asString(),
    onValueChange = { text ->
      val value =
        if (bind.input == "number") {
          text.toDoubleOrNull()?.let { VegaValue.Num(it) } ?: return@OutlinedTextField
        } else {
          VegaValue.Str(text)
        }
      controller.setSignal(input.signal, value)
    },
    label = { Text(input.label, style = MaterialTheme.typography.labelSmall) },
    placeholder =
      bind.attributeText("placeholder")?.let { hint ->
        { Text(hint, style = MaterialTheme.typography.bodySmall) }
      },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
  )
}

@Composable
private fun Label(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    modifier = Modifier.width(96.dp).padding(end = 4.dp),
  )
}
