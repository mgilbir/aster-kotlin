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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.aster.vega.loader.VegaDataLoaders
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.runtime.interaction.CoroutineScheduler
import dev.aster.vega.svg.toSvg
import dev.aster.vegalite.VegaLiteInput
import java.io.File
import kotlinx.coroutines.delay

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
  /** The specification the user pasted or typed, and what compiling it had to say. */
  var pasted by remember { mutableStateOf("") }
  var report by remember { mutableStateOf<PasteReport?>(null) }

  val context = LocalContext.current
  // One text engine for building hand-authored scenes, which the view also draws with. The
  // controller gets a *second* instance: it compiles off the main thread, and one engine must not
  // be
  // touched by two threads at once.
  val textEngine = remember { AndroidTextEngine() }
  val exporter = remember { SceneExporter() }
  // The demo opts in to loading, which no host gets by default, and states exactly what it is
  // opening. A pasted specification's `"url": "data/cars.json"` is resolved against the cache
  // directory first and fetched from `vega.github.io` when it is not there yet, then written to the
  // cache so the next paste of the same example needs no network at all.
  //
  // The allowlist is that one host — `directoryThenNetwork`'s default — and it is the whole of the
  // safety argument here. This screen compiles text a *user pasted*, so an unrestricted loader
  // would
  // let a specification aim this process at any address it liked, which is a request forgery
  // primitive; `169.254.169.254` is cloud credentials and `localhost` is whatever the device runs.
  // Private addresses are refused as well, so the one allowed host cannot be rebound onto the
  // loopback interface.
  val loader =
    remember(context) {
      VegaDataLoaders.directoryThenNetwork(context.cacheDir, cacheDownloads = true)
    }
  // The scheduler is what makes a `debounce` and a **timer** stream work, and its scope is the
  // whole
  // of the lifecycle question: a timer that outlives the composition it draws into is a leak with a
  // repaint attached, and a scope that goes away when the composition does cancels every pending
  // tick without anything having to remember to.
  val scope = rememberCoroutineScope()
  val controller =
    remember(loader, scope) {
      VegaChartController(
        textEngine = AndroidTextEngine(),
        loader = loader,
        scheduler = CoroutineScheduler(scope),
      )
    }

  LaunchedEffect(chart, dark, pasted) {
    val asset = chart.specAsset
    if (chart.isPasted) {
      if (pasted.isBlank()) {
        report = null
        status = "Paste a Vega or Vega-Lite specification, or type one."
      } else {
        // Wait for the typing to stop before compiling.
        //
        // `LaunchedEffect` cancels the previous run whenever `pasted` changes, so this delay is a
        // debounce: a paste is one change and renders in a blink, while typing only ever compiles
        // the text that was left behind. Without it every keystroke queued a compile and the demo
        // stopped answering — 1,484 characters typed into the field produced 1,484 compilations
        // and an ANR.
        delay(PASTE_DEBOUNCE_MILLIS)
        status = "Compiling…"
        // Either grammar. Someone pasting a chart has pasted a chart, not a dialect, so the
        // decision is made here and then *said* — the report leads with which one it took the text
        // for, because a Vega-Lite specification read as Vega would otherwise fail for a reason
        // that reads like nonsense.
        val converted = VegaLiteInput.toVega(pasted)
        val compiled = controller.setSpecAsync(converted.vegaJson ?: pasted)
        report = PasteReport.of(compiled, converted)
        status = report!!.headline
      }
    } else if (asset != null) {
      val json = context.assets.open(asset).bufferedReader().use { it.readText() }
      val compiled = controller.setSpecAsync(VegaLiteInput.toVega(json).vegaJson ?: json)
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

        if (chart.isPasted) {
          PasteControls(
            text = pasted,
            onText = { pasted = it },
            onPaste = {
              val clip = clipboardText(context)
              if (clip == null) status = "Nothing on the clipboard." else pasted = clip
            },
          )
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

        // Whatever controls the specification asked for, between the chart and the buttons. A chart
        // that binds nothing draws nothing here, which is most of them.
        SignalControls(controller)

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

        // Only for a pasted specification: for the bundled ones the diagnostics are ours to fix,
        // not the reader's to act on.
        report
          ?.details
          ?.takeIf { it.isNotEmpty() }
          ?.let { details ->
            Column(
              modifier =
                Modifier.fillMaxWidth()
                  .heightIn(max = 160.dp)
                  .verticalScroll(rememberScrollState())
                  .padding(top = 4.dp)
            ) {
              details.forEach { line ->
                Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
      }
    }
  }
}

/**
 * The paste surface: a button for the common case and a field for everything else.
 *
 * The field is editable rather than read-only because a specification that failed is exactly the
 * one worth changing a line of, and going back to another app to do it loses the diagnostics.
 */
@Composable
private fun PasteControls(text: String, onText: (String) -> Unit, onPaste: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onPaste) { Text("Paste from clipboard") }
      Button(onClick = { onText("") }, enabled = text.isNotEmpty()) { Text("Clear") }
    }
    OutlinedTextField(
      value = text,
      onValueChange = onText,
      modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 150.dp).padding(top = 8.dp),
      label = { Text("Vega specification (JSON)") },
      textStyle = MaterialTheme.typography.bodySmall,
    )
  }
}

/** Long enough to swallow a burst of typing, short enough that a paste feels immediate. */
private const val PASTE_DEBOUNCE_MILLIS = 400L

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
