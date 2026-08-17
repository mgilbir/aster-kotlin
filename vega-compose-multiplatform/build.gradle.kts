plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
}

kotlin {
  androidLibrary {
    namespace = "dev.aster.vega.compose.mp"
    compileSdk = 37
    minSdk = 26
  }
  // The desktop, which for Compose Multiplatform is the JVM with Skia behind it.
  jvm()
  iosArm64()
  iosSimulatorArm64()
  // No macosArm64: Compose Multiplatform treats native macOS as experimental and refuses the target
  // without an opt-in flag. The desktop is the JVM target above, which is where Compose's own
  // desktop support lives, and macOS gets a *Swift* renderer in swift/AsterVegaRender instead.

  compilerOptions { allWarningsAsErrors.set(true) }
  explicitApi()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        api(project(":vega-scene"))
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.ui)
      }
    }
    commonTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(project(":vega-runtime"))
      }
    }
    // Compose on the desktop can rasterise a composable with no window and no display, which is
    // what
    // lets `DrawScopeTargetTest` check actual pixels. Only the JVM source set needs it — the walk's
    // own tests draw into a recording and want nothing from Compose at all.
    jvmTest { dependencies { implementation(compose.desktop.currentOs) } }
  }
}
