import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spotless)
}

allprojects {
  apply(plugin = "com.diffplug.spotless")

  extensions.configure<SpotlessExtension> {
    kotlin {
      target("src/**/*.kt")
      targetExclude("**/build/**")
      ktfmt(rootProject.libs.versions.ktfmt.get()).googleStyle()
      trimTrailingWhitespace()
      endWithNewline()
    }
    kotlinGradle {
      target("*.gradle.kts")
      ktfmt(rootProject.libs.versions.ktfmt.get()).googleStyle()
      trimTrailingWhitespace()
      endWithNewline()
    }
  }
}

/**
 * Shared configuration for the platform-independent Kotlin/JVM core modules.
 *
 * These modules must never see an Android type. `jvmTarget` matches the Android modules' bytecode
 * level so core classes can be consumed unchanged by `vega-android-canvas`.
 */
subprojects {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
      }
      explicitApi()
    }

    extensions.configure<JavaPluginExtension> {
      sourceCompatibility = JavaVersion.VERSION_17
      targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
      add("testImplementation", platform(rootProject.libs.junit.bom))
      add("testImplementation", rootProject.libs.junit.jupiter)
      add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      // Goldens are only rewritten when explicitly requested; see :updateGoldens.
      systemProperty(
        "vega.updateGoldens",
        providers.gradleProperty("updateGoldens").getOrElse("false"),
      )
      testLogging { showStandardStreams = false }
    }
  }
}

/**
 * Explicit, opt-in golden regeneration. Normal test runs never rewrite goldens; see
 * PROJECT_BRIEF.md section 18.3.
 */
tasks.register("updateGoldens") {
  group = "verification"
  description = "Regenerates scene-snapshot and SVG goldens. Review the diff as a rendering change."
  doFirst {
    throw GradleException(
      "Run as: ./gradlew test -PupdateGoldens=true --rerun-tasks (see PROJECT_BRIEF.md 18.3)"
    )
  }
}
