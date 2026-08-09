plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-model"))
  api(project(":vega-expression"))
  // `heatmap` turns a colour expression's answer into pixels, and reading a CSS colour is
  // `vega-scene`'s job — the same one-way dependency `vega-expression` already takes for
  // `luminance()`, and for the same reason: 148 colour names should have one home.
  implementation(project(":vega-scene"))
}
