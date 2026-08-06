plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-scene"))
  testImplementation(project(":test-fixtures"))
}
