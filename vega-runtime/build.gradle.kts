plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  testImplementation(project(":vega-loader"))
  api(project(":vega-model"))
  api(project(":vega-expression"))
  api(project(":vega-dataflow"))
  api(project(":vega-scene"))
  api(libs.kotlinx.coroutines.core)
  testImplementation(project(":test-fixtures"))
  // The Vega-Lite compiler emits a specification; proving that it draws upstream's chart needs the
  // scene comparison, which lives here. A test-only dependency, and one-directional: `:vega-lite`
  // itself knows nothing about the runtime.
  testImplementation(project(":vega-lite"))
  // Only so FixtureSvgTest can write a lookable-at rendering of each fixture beside the oracle's.
  testImplementation(project(":vega-svg"))
  testImplementation(libs.kotlinx.coroutines.test)
}
