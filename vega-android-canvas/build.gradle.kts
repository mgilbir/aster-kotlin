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
