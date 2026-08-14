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
      // The sources stay where they are. Moving 200 files into `src/commonMain/kotlin` would make
      // every one of them look changed in a review whose actual subject is the build.
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        api(libs.kotlinx.serialization.json)
        // Calendar arithmetic for dates. JetBrains' recommendation for shared Kotlin Multiplatform
        // code, which is what keeps the core off java.time.
        api(libs.kotlinx.datetime)
      }
    }
    jvmTest { kotlin.srcDir("src/test/kotlin") }
  }
}
