package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaJson
import java.io.File
import org.junit.jupiter.api.Test

class GallerySweep {
  @Test
  fun sweep() {
    val root =
      File(
        "/private/tmp/claude-502/-Users-m-gilbiraud-Projects-mgilbir-aster-kotlin/f95af2e1-26e9-4e8e-a06b-e157c65da3d5/scratchpad/gallery"
      )
    if (!root.isDirectory) return
    val specs = File(root, "examples/specs")
    val upstream = File(root, "upstream")
    val lines = mutableListOf<String>()
    var clean = 0
    for (file in specs.listFiles()!!.filter { it.name.endsWith(".vl.json") }.sortedBy { it.name }) {
      val name = file.name.removeSuffix(".vl.json")
      val expectedFile = File(upstream, "$name.vega.json")
      if (!expectedFile.exists()) continue
      val compiled = VegaLiteCompiler().compileJson(file.readText())
      val fatal = compiled.diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
      val vega = compiled.vega
      if (vega == null) {
        lines += "$name\tREFUSED\t0\t${fatal.firstOrNull()?.message?.take(160)}"
        continue
      }
      val differences = SpecDiff.compare(VegaJson.parse(expectedFile.readText()), vega)
      if (differences.isEmpty() && fatal.isEmpty()) clean++
      else lines += "$name\t${differences.size}\t${fatal.size}\t${differences.joinToString(" | ")}"
    }
    File(root, "sweep.tsv").writeText(lines.joinToString("\n"))
    println("GALLERY clean=$clean differing=${lines.size}")
  }
}
