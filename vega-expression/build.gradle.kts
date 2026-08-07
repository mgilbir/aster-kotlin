plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":vega-model"))
  // For `luminance(color)`, which has to read a CSS colour string. SceneColor already parses every
  // form d3-color does, including the named-colour table; a second parser here would be the same
  // 148 names again and a second place for them to drift. The dependency is one-way — vega-scene
  // knows nothing about expressions — and `implementation` keeps it out of this module's API.
  implementation(project(":vega-scene"))
}
