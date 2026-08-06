// Deliberately free of any test framework dependency: `demo` and `benchmark` consume the sample
// scenes from this module, and pulling JUnit into an APK would be wrong.
plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-scene"))
  api(project(":vega-svg"))
}
