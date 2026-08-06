package dev.aster.vega.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aster.vega.android.AndroidTextEngine
import dev.aster.vega.android.BitmapExportOptions
import dev.aster.vega.android.SceneExporter
import dev.aster.vega.compose.VegaChart
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.svg.toSvg
import java.io.File

/**
 * Demonstrates the whole surface: hand-authored scenes and compiled Vega specifications rendered
 * through the Canvas backend, the Compose API hosting the canonical View, interaction, and SVG, PNG
 * and PDF export.
 *
 * The specification entries load Vega JSON from the app's assets and compile it on a background
 * thread, which is what a user of this library does. They are the same fixtures the differential
 * tests compare against upstream, so what appears here is what was proved correct.
 */
public class DemoActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { DemoScreen() }
  }
}

@Composable
private fun DemoScreen() {
  var chart by remember { mutableStateOf(DemoChart.BAR) }
  var dark by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("Tap a mark, drag to pan, pinch to zoom.") }

  val context = LocalContext.current
  // One text engine for building hand-authored scenes, which the view also draws with. The
  // controller gets a *second* instance: it compiles off the main thread, and one engine must not
  // be
  // touched by two threads at once.
  val textEngine = remember { AndroidTextEngine() }
  val exporter = remember { SceneExporter() }
  val controller = remember { VegaChartController(textEngine = AndroidTextEngine()) }

  LaunchedEffect(chart, dark) {
    val asset = chart.specAsset
    if (asset != null) {
      val json = context.assets.open(asset).bufferedReader().use { it.readText() }
      val compiled = controller.setSpecAsync(json)
      val errors = compiled.diagnostics.count { it.severity >= DiagnosticSeverity.ERROR }
      status =
        if (!compiled.isUsable) "$asset did not compile; see diagnostics"
        else if (errors > 0) "$asset compiled with $errors error(s)"
        else "$asset compiled: ${compiled.diagnostics.size} diagnostic(s)"
    } else {
      chart.build(textEngine, dark)?.let { controller.setScene(it) }
      status = "Tap a mark, drag to pan, pinch to zoom."
    }
  }

  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(modifier = Modifier.fillMaxSize()) {
      // safeDrawingPadding keeps the controls out from under the status and navigation bars, which
      // are drawn over the window by default on recent Android versions.
      Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          DemoChart.entries.forEach { entry ->
            FilterChip(
              selected = chart == entry,
              onClick = { chart = entry },
              label = { Text(entry.label) },
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text("Dark background")
          Switch(checked = dark, onCheckedChange = { dark = it }, enabled = !chart.isSpec)
          Button(onClick = { controller.resetViewport() }) { Text("Reset zoom") }
        }

        Box(
          modifier =
            Modifier.fillMaxWidth()
              .weight(1f)
              .padding(vertical = 8.dp)
              .background(if (dark) Color(0xFF1C1F24) else Color.White)
        ) {
          VegaChart(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            onEvent = { event -> status = describe(event) ?: status },
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = {
              val file = File(context.cacheDir, "chart.svg")
              // Whatever is on screen, hand-authored or compiled — the exporters do not care which.
              file.writeText(controller.snapshot.scene.toSvg())
              status = "Wrote SVG (${file.length()} bytes) to ${file.name}"
            }
          ) {
            Text("Export SVG")
          }
          Button(
            onClick = {
              val export =
                exporter.toPng(
                  controller.snapshot.scene,
                  BitmapExportOptions(width = 1200.0, height = 800.0, pixelScale = 2f),
                )
              val file = File(context.cacheDir, "chart.png")
              file.writeBytes(export.bytes)
              status = "Wrote PNG (${export.bytes.size} bytes), ${export.warnings.size} warnings"
            }
          ) {
            Text("Export PNG")
          }
          Button(
            onClick = {
              val export =
                exporter.toPdf(
                  controller.snapshot.scene,
                  widthPoints = 1200.0,
                  heightPoints = 800.0,
                )
              val file = File(context.cacheDir, "chart.pdf")
              file.writeBytes(export.bytes)
              status = "Wrote PDF (${export.bytes.size} bytes), ${export.warnings.size} warnings"
            }
          ) {
            Text("Export PDF")
          }
        }

        Text(text = status, modifier = Modifier.padding(top = 8.dp))
      }
    }
  }
}

private fun describe(event: ChartEvent): String? =
  when (event) {
    is ChartEvent.MarkClicked -> "Clicked ${event.markName ?: "mark"} ${event.datum ?: ""}"
    is ChartEvent.SelectionChanged ->
      if (event.selection.isEmpty) "Selection cleared"
      else "Selected ${event.selection.nodeIds.size}"
    is ChartEvent.MarkLongPressed -> "Long pressed ${event.markName ?: "mark"}"
    is ChartEvent.ViewportChanged -> "Viewport moved"
    // Hover and tooltip updates fire constantly; they would drown out the useful messages.
    is ChartEvent.MarkHovered,
    is ChartEvent.TooltipChanged,
    is ChartEvent.SignalChanged -> null
  }
