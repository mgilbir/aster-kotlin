plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  // The compiler's whole output is a Vega specification in the runtime's value model, so this is
  // the
  // only dependency it needs: it emits Vega, it does not execute it. What the emitted specification
  // then *draws* is checked in `:vega-runtime`, where the scene comparison already lives.
  api(project(":vega-model"))
}
