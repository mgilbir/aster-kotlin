plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-model"))
  api(project(":vega-expression"))
  api(project(":vega-dataflow"))
  api(project(":vega-scene"))
  api(libs.kotlinx.coroutines.core)
  // Calendar arithmetic for time scales. A date is not a fixed number of milliseconds, and the core
  // must stay free of java.time so it can move to Kotlin Multiplatform unchanged.
  api(libs.kotlinx.datetime)
  testImplementation(project(":test-fixtures"))
  // Only so FixtureSvgTest can write a lookable-at rendering of each fixture beside the oracle's.
  testImplementation(project(":vega-svg"))
  testImplementation(libs.kotlinx.coroutines.test)
}
