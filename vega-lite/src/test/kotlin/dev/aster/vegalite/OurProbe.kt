package dev.aster.vegalite

import org.junit.jupiter.api.Test

class OurProbe {
  @Test
  fun probe() {
    val spec =
      java.io
        .File(
          "/Users/m.gilbiraud/Projects/mgilbir/aster-kotlin/build/vega-lite-wild-corpus/docs/data/chart/vl_0272.vl.json"
        )
        .readText()
    VegaLiteCompiler().compileJson(spec).toJson()
  }
}
