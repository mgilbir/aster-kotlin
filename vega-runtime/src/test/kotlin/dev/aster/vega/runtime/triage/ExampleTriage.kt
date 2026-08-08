package dev.aster.vega.runtime.triage

import dev.aster.vega.loader.VegaDataLoaders
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import org.junit.jupiter.api.Test

class ExampleTriage {
  @Test
  fun triage() {
    val dir = File(System.getProperty("examples.dir") ?: return)
    if (!dir.isDirectory) return
    val files = dir.listFiles { f -> f.name.endsWith(".vg.json") }!!.sortedBy { it.name }
    // The examples name their data relatively, as `data/x.json`, so the corpus directory is the
    // root — and what is not in it is fetched from where the Vega project publishes it and kept, so
    // a corpus downloaded as bare specifications fills itself in on the first run and is offline
    // from the second. This is a survey run by hand, not a gate; the differential tests read only
    // from disk.
    val loader = VegaDataLoaders.directoryThenNetwork(dir, cacheDownloads = true)
    var clean = 0
    val byMessage = sortedMapOf<String, Int>()
    val rows = mutableListOf<String>()
    for (f in files) {
      // Each example gets its own thread and a hard deadline. One specification that loops
      // forever must not take the survey with it — and *which* one loops is itself a finding.
      val started = System.nanoTime()
      val holder = arrayOfNulls<Any>(1)
      val worker = Thread {
        holder[0] = runCatching { SpecCompiler(loader = loader).compileJson(f.readText()) }
      }
      worker.isDaemon = true
      worker.start()
      worker.join(20_000)
      val millis = (System.nanoTime() - started) / 1_000_000
      @Suppress("UNCHECKED_CAST") val outcome = holder[0] as Result<CompiledSpec>?
      if (outcome == null) {
        rows += "HANG   ${f.name}  still running after 20s"
        byMessage.merge("HANG (no result in 20s)", 1, Int::plus)
        continue
      }
      val c = outcome.getOrElse { t ->
        rows += "CRASH  ${f.name}  ${t::class.simpleName}: ${t.message?.take(90)}"
        byMessage.merge("CRASH ${t::class.simpleName}", 1, Int::plus)
        continue
      }
      if (millis > 2000) rows += "SLOW   ${f.name}  ${millis}ms"
      val errs = c.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
      val warns = c.diagnostics.size - errs.size
      // The *first* error is the root; everything after it is usually a cascade from the same
      // scale or dataset failing, and counting those buries the cause under its symptoms.
      errs.firstOrNull()?.let {
        byMessage.merge("${it.code} | ${it.message.take(72)}", 1, Int::plus)
      }
      val marks = c.scene?.nodeCount ?: 0
      when {
        !c.isUsable -> rows += "NOSCENE ${f.name}  ${errs.firstOrNull()?.message?.take(80)}"
        errs.isEmpty() -> {
          clean++
          rows += "OK     ${f.name}  ${marks} nodes, $warns warn"
        }
        else -> rows += "ERRS   ${f.name}  ${marks} nodes, ${errs.size} err, $warns warn"
      }
    }
    val out = StringBuilder()
    out.appendLine("=== TRIAGE ${files.size} examples: $clean clean ===")
    rows.forEach { out.appendLine(it) }
    out.appendLine("=== TOP ERRORS ===")
    byMessage.entries
      .sortedByDescending { it.value }
      .forEach { out.appendLine("${it.value}\t${it.key}") }
    File(dir, "triage-report.txt").writeText(out.toString())
    println(out)
  }
}
