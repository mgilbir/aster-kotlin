package dev.aster.vega.demo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.vega.runtime.FocusRing
import dev.aster.vega.scene.SceneColor

/**
 * The focus ring, as a **host** would configure it.
 *
 * The library draws one ring for the keyboard and leaves its appearance to whoever embeds the
 * chart, because what reads as "clearly focused" depends on the surface around it. This is that
 * decision made three ways so it can be looked at rather than argued about, and it is deliberately
 * demo code: none of these presets is the engine's opinion.
 *
 * **A quieter ring has a floor.** This is what tells a reader moving by arrow key where they are,
 * so it is an accessibility affordance and not decoration. WCAG's focus-appearance guidance asks
 * for an indicator about two units thick at 3:1 contrast against what is behind it — `Hairline`
 * below is under that on both counts, and is included because seeing where the line is drawn is the
 * point of having the control.
 */
internal enum class RingPreset(val label: String, val ring: FocusRing) {
  /**
   * The engine's default: solid, two units, the blue every platform's focus indicator converges on.
   */
  DEFAULT("Default", FocusRing()),

  /**
   * Quieter without going quiet: the same colour and thickness, dashed and standing further off.
   *
   * Dashed rather than thinner or paler, which is the point of having `dash` at all — a hairline at
   * low alpha loses the contrast that makes a ring findable, where a dash keeps it and simply reads
   * as less of a border.
   */
  SUBTLE(
    "Subtle",
    FocusRing(inset = 4.0, width = 2.0, dash = listOf(3.0, 3.0)),
  ),

  /** Below the accessibility floor, and here so that the floor is visible rather than asserted. */
  HAIRLINE(
    "Hairline",
    FocusRing(
      inset = 3.0,
      width = 1.0,
      colour = SceneColor(0x1A / 255.0, 0x73 / 255.0, 0xE8 / 255.0, alpha = 0.45),
    ),
  ),

  /** For a surface where the chart is busy and the ring has to win. */
  BOLD("Bold", FocusRing(inset = 3.0, width = 3.0)),
}

/**
 * Picks a ring and says whether a **tap** draws one.
 *
 * The tap switch is the other half of the host's decision. The engine's answer is no — a tap
 * already says what it did through the tooltip, and an outline on every touch is clutter — but a
 * kiosk driven entirely by touch, where nothing else marks the current item, wants the opposite.
 */
@Composable
internal fun FocusRingControls(
  preset: RingPreset,
  onPreset: (RingPreset) -> Unit,
  onPointer: Boolean,
  onOnPointer: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("Focus ring")
    RingPreset.entries.forEach { entry ->
      FilterChip(
        selected = preset == entry,
        onClick = { onPreset(entry) },
        label = { Text(entry.label) },
      )
    }
    Text("on tap")
    Switch(checked = onPointer, onCheckedChange = onOnPointer)
  }
}
