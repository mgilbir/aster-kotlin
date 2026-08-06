plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-model"))
  api(project(":vega-expression"))
  api(project(":vega-dataflow"))
  api(project(":vega-scene"))
  api(libs.kotlinx.coroutines.core)
  testImplementation(project(":test-fixtures"))
  testImplementation(libs.kotlinx.coroutines.test)
}
