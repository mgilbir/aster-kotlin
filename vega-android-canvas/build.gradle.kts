plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "dev.aster.vega.android"
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
  api(project(":vega-scene"))
  api(project(":vega-runtime"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.customview)
  implementation(libs.kotlinx.coroutines.android)

  androidTestImplementation(project(":test-fixtures"))
  androidTestImplementation(libs.junit4)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.ext.junit)
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
