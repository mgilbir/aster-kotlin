plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
  jvm()
  macosArm64()
  iosArm64()
  iosSimulatorArm64()
  linuxX64()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        api(project(":vega-model"))
        api(project(":vega-expression"))
        api(project(":vega-dataflow"))
        api(project(":vega-scene"))
        api(libs.kotlinx.coroutines.core)
      }
    }
    jvmTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies {
        // The loader and the fixtures are JVM scaffolding — a file on disk and a socket — which is
        // why they are the one pair of dependencies that does not follow the core off the JVM.
        implementation(project(":vega-loader"))
        implementation(project(":test-fixtures"))
        // The Vega-Lite compiler emits a specification; proving that it draws upstream's chart
        // needs the scene comparison, which lives here. A test-only dependency, and
        // one-directional: `:vega-lite` itself knows nothing about the runtime.
        implementation(project(":vega-lite"))
        // Only so FixtureSvgTest can write a lookable-at rendering of each fixture beside the
        // oracle's.
        implementation(project(":vega-svg"))
        implementation(libs.kotlinx.coroutines.test)
      }
    }
  }
}
