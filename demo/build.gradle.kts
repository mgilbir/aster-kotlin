plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.aster.vega.demo"
  compileSdk = 37

  defaultConfig {
    applicationId = "dev.aster.vega.demo"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
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

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

dependencies {
  // so
  implementation(project(":vega-compose"))
  implementation(project(":test-fixtures"))
  implementation(project(":vega-android-canvas"))
  // The JVM loader seam, so a pasted specification's `"url": "data/..."` resolves.
  implementation(project(":vega-loader"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.tooling)

  androidTestImplementation(libs.junit4)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.espresso.core)
}
