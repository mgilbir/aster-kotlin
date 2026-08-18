plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.aster.vega.compose"
  compileSdk = 37

  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // java.time.
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
  // so
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

// An Android library publishes nothing until a variant is chosen: there are two, and Gradle will
// not
// guess. `release` is the one a consumer wants, and `withSourcesJar` because Central asks for
// sources and the Android plugin does not add them by default.
android { publishing { singleVariant("release") { withSourcesJar() } } }

// `afterEvaluate`, because the Android plugin creates the `release` component while it evaluates
// and
// it does not exist yet when this file is read.
afterEvaluate {
  publishing {
    publications { register<MavenPublication>("release") { from(components["release"]) } }
  }
}
