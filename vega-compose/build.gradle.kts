plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.aster.vega.compose"
  compileSdk = 37

  defaultConfig {
    minSdk = 23
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }

  lint {
    warningsAsErrors = true
    abortOnError = true
  }
}

kotlin {
  compilerOptions { allWarningsAsErrors.set(true) }
  explicitApi()
}

dependencies {
  api(project(":vega-android-canvas"))
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  compileOnly(libs.compose.tooling.preview)

  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(project(":test-fixtures"))
  androidTestImplementation(libs.junit4)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.activity.compose)
  // Supplies the host activity the instrumented tests compose into. Compose's own test rule is
  // deliberately not used; see VegaChartComposeTest.
  debugImplementation(libs.compose.ui.test.manifest)
}
