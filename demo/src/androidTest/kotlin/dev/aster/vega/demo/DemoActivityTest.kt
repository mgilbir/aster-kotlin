package dev.aster.vega.demo

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.android.AndroidTextEngine
import dev.aster.vega.loader.VegaDataLoaders
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.flatten
import dev.aster.vega.scene.toCanonicalJson
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoActivityTest {

  @Test
  fun activityLaunches() {
    ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> assertTrue(!activity.isFinishing) }
    }
  }

  /**
   * The loader the demo wires, doing the thing it is wired for: fetching a gallery example's data.
   *
   * On the device, over a real socket, because that is the part that cannot be proved anywhere else
   * — the JVM tests use a fake transport, and a missing `INTERNET` permission or an Android policy
   * the desktop JVM does not have would show up here and nowhere before here.
   *
   * Skipped rather than failed when the device has no route out: an emulator without networking is
   * a fact about the machine, not a regression in the demo.
   */
  @Test
  fun aPastedSpecificationLoadsItsDataFromTheGallery() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cache = File(context.cacheDir, "loader-test").apply { deleteRecursively() }
    val loader = VegaDataLoaders.directoryThenNetwork(cache, cacheDownloads = true)

    val rows =
      try {
        loader.load("data/barley.json")
      } catch (unreachable: IOException) {
        Assume.assumeNoException("no route to vega.github.io from this device", unreachable)
        return
      }
    assertTrue("fetched nothing", rows.contains("\"variety\""))

    // Cached where the next load will find it, so the second read needs no network.
    assertTrue("nothing was cached", File(cache, "data/barley.json").isFile)

    // And the whole way through: a specification naming that URL compiles into marks.
    val controller = VegaChartController(textEngine = AndroidTextEngine(), loader = loader)
    val compiled =
      controller.setSpec(
        """
        {"width": 200, "height": 100, "padding": 5,
         "data": [{"name": "barley", "url": "data/barley.json"}],
         "scales": [{"name": "x", "type": "linear", "range": "width",
                     "domain": {"data": "barley", "field": "yield"}}],
         "marks": [{"type": "symbol", "from": {"data": "barley"},
                    "encode": {"enter": {"x": {"scale": "x", "field": "yield"},
                                         "y": {"value": 50}}}}]}
        """
      )
    assertTrue(
      "compiled with errors: ${compiled.diagnostics}",
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
    )
    assertEquals(120, controller.snapshot.scene.flatten().count { it.node.metadata.role == "mark" })
  }

  @Test
  fun everyHandAuthoredChartBuildsInBothThemes() {
    val engine = AndroidTextEngine()
    for (chart in DemoChart.entries.filter { !it.isSpec }) {
      for (dark in listOf(false, true)) {
        val scene = requireNotNull(chart.build(engine, dark)) { "${chart.label} built nothing" }
        assertTrue("${chart.label} produced an empty scene", scene.nodeCount > 1)
        assertTrue("${chart.label} has no size", scene.width > 0 && scene.height > 0)
      }
    }
  }

  /**
   * Every bundled specification compiles on the device, with the device's own text metrics.
   *
   * The differential tests prove these specifications match upstream using a stand-in text engine;
   * this proves they also survive real font measurement, which is what the chart on screen uses.
   *
   * One of them names its data by **URL** — `job-voyager` reads the 900KB file the gallery hosts —
   * and that one alone is given the loader the demo itself uses, which fetches once and then reads
   * the cache directory. The rest keep the refusing default on purpose: a specification with its
   * data inline must not need a network to compile, and using one loader for all of them would hide
   * it if one day one did.
   */
  @Test
  fun everySpecificationCompilesOnDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Bundled ones only: the paste entry is a specification too, and its text comes from a user.
    for (chart in DemoChart.entries.filter { it.specAsset != null }) {
      val asset = requireNotNull(chart.specAsset)
      val json = context.assets.open(asset).bufferedReader().use { it.readText() }
      val controller =
        if (""""url"""" in json) {
          VegaChartController(
            textEngine = AndroidTextEngine(),
            loader = VegaDataLoaders.directoryThenNetwork(context.cacheDir, cacheDownloads = true),
          )
        } else {
          VegaChartController(textEngine = AndroidTextEngine())
        }
      val compiled = controller.setSpec(json)

      assertTrue("$asset produced no scene", compiled.isUsable)
      val errors = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
      assertTrue("$asset reported $errors", errors.isEmpty())
      assertTrue("$asset drew nothing", controller.snapshot.scene.nodeCount > 1)
    }
  }

  @Test
  fun darkThemeChangesChromeButNotGeometry() {
    val engine = AndroidTextEngine()
    val light = requireNotNull(DemoChart.BAR.build(engine, dark = false))
    val dark = requireNotNull(DemoChart.BAR.build(engine, dark = true))

    assertTrue("background should differ", light.background != dark.background)
    // Colours differ, but the marks must land in exactly the same places.
    assertNotEquals(light.toCanonicalJson(), dark.toCanonicalJson())
    assertEquals(light.nodeCount, dark.nodeCount)
    assertEquals(light.contentBounds, dark.contentBounds)
  }

  // ---- pasting a specification ----------------------------------------------

  private val goodSpec =
    """
    {
      "description": "A pasted bar chart.",
      "width": 200, "height": 100,
      "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
      ],
      "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
        "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}}}}]
    }
    """
      .trimIndent()

  private fun compileOnDevice(json: String) =
    VegaChartController(textEngine = AndroidTextEngine()).setSpec(json)

  @Test
  fun aPastedSpecificationCompilesAndReportsCleanly() {
    val report = PasteReport.of(compileOnDevice(goodSpec))
    assertTrue(report.headline, report.headline.contains("nothing unsupported"))
    assertEquals(emptyList<String>(), report.details)
  }

  /** Nonsense keeps the previous chart on screen, and the report has to say so. */
  @Test
  fun aPastedSpecificationThatIsNotJsonSaysSoRatherThanBlanking() {
    val report = PasteReport.of(compileOnDevice("not json at all"))
    assertTrue(report.headline, report.headline.contains("Did not compile"))
    assertTrue(report.headline, report.headline.contains("previous chart"))
    assertTrue(report.details.isNotEmpty())
  }

  /**
   * The case the whole feature exists for.
   *
   * A real-world specification will use something this engine has not implemented. It renders — and
   * a reader has to be told it is not the chart that was asked for, with the property named. This
   * is "nothing silently ignored" reaching an actual user for the first time.
   */
  @Test
  fun aPastedSpecificationUsingSomethingUnsupportedSaysWhichPart() {
    // A mark type that cannot exist, rather than a real one this engine has not implemented yet.
    //
    // That is the whole point of the change: this test used to use `shape`, and it started failing
    // the
    // day `shape` was implemented — asserting on a gap means the test breaks when the gap closes,
    // which
    // is the opposite of what a regression test should do. A name that is unknown by construction
    // keeps
    // testing the reporting rather than the engine's coverage.
    val withUnsupported =
      goodSpec.replace(
        """"marks": [{"type": "rect",""",
        """"marks": [{"type": "nonesuchMark"}, {"type": "rect",""",
      )
    val report = PasteReport.of(compileOnDevice(withUnsupported))
    assertTrue(report.headline, report.headline.contains("not the chart the specification asked"))
    // Errors and warnings are counted separately: one means the picture is missing something, the
    // other that it is very nearly right.
    assertTrue(report.headline, report.headline.contains("1 thing could not be drawn"))
    assertTrue(report.details.toString(), report.details.any { it.contains("nonesuchMark") })
  }

  /**
   * A property that is merely ignored is a softer message than a mark that never drew.
   *
   * The channel name is unknown by construction, for the same reason as above: this used to say
   * `cornerRadiusTopLeft`, and it began failing the day per-corner radii were implemented.
   */
  @Test
  fun anIgnoredPropertyIsReportedWithoutClaimingTheChartIsWrong() {
    val withIgnored =
      goodSpec.replace(
        """"y2": {"scale": "y", "value": 0}""",
        """"y2": {"scale": "y", "value": 0}, "nonesuchProperty": {"value": 4}""",
      )
    val report = PasteReport.of(compileOnDevice(withIgnored))
    assertTrue(report.headline, !report.headline.contains("could not be drawn"))
    assertTrue(report.headline, report.headline.contains("1 property was ignored"))
  }

  /**
   * The clipboard read, against a real `ClipboardManager` and a focused activity.
   *
   * The activity has to be launched, and that is the finding rather than a test detail: since
   * Android 10 an app may only read the clipboard while it holds focus, and `getPrimaryClip()`
   * returns null otherwise. It works in the demo because a person taps the button with the app in
   * front of them — but the null path is a real one, not just an empty clipboard, which is why the
   * button says something rather than silently doing nothing.
   */
  @Test
  fun theClipboardIsReadBackAsTextWhileTheAppHasFocus() {
    ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
      var read: String? = null
      var readBlank: String? = "not yet run"
      scenario.onActivity { activity ->
        val clipboard =
          activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("spec", goodSpec))
        read = clipboardText(activity)
        // Whitespace alone is not a specification and must not look like one.
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("blank", "   "))
        readBlank = clipboardText(activity)
      }
      assertEquals(goodSpec, read)
      assertEquals(null, readBlank)
    }
  }

  @Test
  fun theCatalogueOffersAPasteEntry() {
    val pasted = DemoChart.entries.filter { it.isPasted }
    assertEquals(1, pasted.size)
    assertTrue(pasted.single().isSpec)
    assertEquals(null, pasted.single().specAsset)
  }
}
