plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "dev.aster.vega.benchmark"
  compileSdk = 37

  defaultConfig {
    minSdk = 23
    testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testBuildType = "release"

  buildTypes {
    debug {
      // Benchmarks must not run against a debuggable build; see PROJECT_BRIEF.md 18.6.
      isDefault = false
    }
    release { isDefault = true }
  }

  lint {
    warningsAsErrors = true
    abortOnError = true
  }
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

dependencies {
  androidTestImplementation(project(":test-fixtures"))
  androidTestImplementation(project(":vega-scene"))
  androidTestImplementation(project(":vega-svg"))
  androidTestImplementation(project(":vega-android-canvas"))
  androidTestImplementation(libs.benchmark.junit4)
  androidTestImplementation(libs.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
