plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
  jvm()
  macosArm64()
  iosArm64()
  iosSimulatorArm64()
  linuxX64()

  sourceSets {
    commonMain {
      // The sources stay where they are, as `:vega-model`'s do: moving twenty-seven files into
      // `src/commonMain/kotlin` would make every one of them look changed in a review whose actual
      // subject is the build.
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        // The compiler's whole output is a Vega specification in the runtime's value model, so this
        // is the only dependency it needs: it emits Vega, it does not execute it. What the emitted
        // specification then *draws* is checked in `:vega-runtime`, where the scene comparison
        // lives.
        api(project(":vega-model"))
      }
    }
    // The differential tests read fixtures off disk and use JUnit, so they stay on the JVM. Nothing
    // in the compiler itself does, which is why it can be built for every target at all.
    jvmTest { kotlin.srcDir("src/test/kotlin") }
  }
}
