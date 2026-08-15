plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvm()
  macosArm64()
  iosArm64()
  iosSimulatorArm64()
  linuxX64()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies { api(project(":vega-model")) }
    }
    jvmTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies { implementation(project(":test-fixtures")) }
    }
  }
}
