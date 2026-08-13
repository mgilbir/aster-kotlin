package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import java.io.File
import org.junit.jupiter.api.Test

class ProbeOne {
  @Test
  fun probe() {
    val root =
      File(
        "/private/tmp/claude-502/-Users-m-gilbiraud-Projects-mgilbir-aster-kotlin/f95af2e1-26e9-4e8e-a06b-e157c65da3d5/scratchpad"
      )
    val which = File(root, "probe.txt").takeIf { it.exists() }?.readText()?.trim() ?: return
    val spec = File(root, "gallery/examples/specs/$which.vl.json")
    val compiled = VegaLiteCompiler().compileJson(spec.readText())
    File(root, "ours.json").writeText(compiled.vega?.let { VegaJson.write(it) } ?: "REFUSED")
    compiled.diagnostics.forEach { println("DIAG ${it.severity} ${it.message}") }
    val expected = VegaJson.parse(File(root, "gallery/upstream/$which.vega.json").readText())
    val ours = compiled.vega
    if (ours != null) {
      SpecDiff.compare(expected, ours).forEach { println("D $it") }
    }
  }
}
