plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  testImplementation(project(":vega-loader"))
  api(project(":vega-model"))
  api(project(":vega-expression"))
  api(project(":vega-dataflow"))
  api(project(":vega-scene"))
  api(libs.kotlinx.coroutines.core)
  testImplementation(project(":test-fixtures"))
  // Only so FixtureSvgTest can write a lookable-at rendering of each fixture beside the oracle's.
  testImplementation(project(":vega-svg"))
  testImplementation(libs.kotlinx.coroutines.test)
}
