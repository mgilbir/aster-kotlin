plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-model"))
  api(project(":vega-expression"))
}
