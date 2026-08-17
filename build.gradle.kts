import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * The zone every test runs in.
 *
 * Europe/Amsterdam, because it observes daylight saving and is one hour off UTC in winter — so a
 * bug that only shows up away from UTC, or only across a transition, shows up here.
 */
val TEST_TIME_ZONE = "Europe/Amsterdam"

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
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
  /**
   * The same core modules, compiled for every target they claim to support.
   *
   * `jvm` is what Android consumes and what every test runs on; the Apple and Linux targets are
   * there to *prove* the portability the core has always claimed rather than to be shipped, and
   * they earn their build time — a JVM-only API in common code fails here and nowhere else, which
   * is a stronger guarantee than the grep in `NoAndroidTypesTest` that used to stand in for it.
   *
   * Sources stay in `src/main/kotlin` and `src/test/kotlin`; see each module's `srcDir`.
   */
  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
      compilerOptions { allWarningsAsErrors.set(true) }
      explicitApi()
      // Bytecode level, not a toolchain: the same JDK builds everything, and 17 is what the Android
      // modules consume. Asking for a 17 *toolchain* would demand a second JDK on every machine
      // that builds this for no gain.
      targets.withType<KotlinJvmTarget>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
      }
      // `test` means `jvmTest` here. Without this alias `./gradlew test` matches **no task** in a
      // multiplatform module and reports success — which is precisely the "gate that prints passed
      // without having run" that PROJECT_BRIEF.md 18 exists to prevent. It went green exactly once
      // that way, which is once more than it should have.
      if (tasks.findByName("test") == null) {
        tasks.register("test") {
          group = "verification"
          description = "Runs the JVM tests; an alias so `test` means the same in every module."
          dependsOn("jvmTest")
        }
      }

      // Lazily, because this runs as the plugin is applied and `jvmTest` does not exist until the
      // module's own `kotlin { jvm() }` has declared the target.
      sourceSets.configureEach {
        // `commonTest` runs on **every** target, which is the point: a test here executes on
        // Kotlin/Native as well as on the JVM, and that is the only way portability stops being a
        // compile-time claim. `kotlin("test")` is the assertion library that exists on all of them;
        // the JUnit 5 suites stay in `jvmTest`, where they read files and need a real framework.
        if (name == "commonTest") {
          dependencies { implementation(kotlin("test")) }
        }
        if (name == "jvmTest") {
          dependencies {
            // A source-set dependency block has no `platform()` of its own; the handler does.
            implementation(project.dependencies.platform(rootProject.libs.junit.bom))
            implementation(rootProject.libs.junit.jupiter)
            runtimeOnly(rootProject.libs.junit.platform.launcher)
          }
        }
      }
    }
  }

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
  }
}

subprojects {
  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Vega's cross-filter example carries 200,000 rows through three `bin` transforms and a
    // `crossfilter`, and this engine's transforms copy rather than mutate — so the peak live set
    // is several times the input where upstream's is one array. The JVM's default heap is a
    // quarter of physical memory, which is whatever the machine happens to have; pinning it makes
    // the gate mean the same thing on a laptop and on CI, and 2 GB is roughly four times what
    // that fixture actually needs.
    maxHeapSize = "2g"
    // A `time` scale is *local*, so its output depends on the machine's zone. Pinning one makes
    // the differential references reproducible off this machine, and lets a fixture cross a
    // daylight-saving boundary deliberately — which is where local time scales actually break.
    // `scripts/oracle.sh` exports the same zone to Node, so both sides agree by construction
    // rather than by coincidence.
    environment("TZ", TEST_TIME_ZONE)
    systemProperty("user.timezone", TEST_TIME_ZONE)
    // Lets a scratch triage run point at a directory of specs outside the repository.
    providers.systemProperty("examples.dir").orNull?.let { systemProperty("examples.dir", it) }

    // The differential tests read the fixtures and their upstream references straight off disk,
    // so Gradle cannot see them and will call the task up to date after `scripts/oracle.sh` has
    // rewritten every reference. That is worse than it sounds: the gate prints "Differential
    // tests passed" without having run, and a fixture that disagrees with upstream gets
    // committed behind a green check. Declaring them as inputs is what makes the gate mean
    // something.
    inputs
      .dir(rootProject.layout.projectDirectory.dir("test-fixtures/specs"))
      .withPropertyName("differentialFixtures")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
      .dir(rootProject.layout.projectDirectory.dir("test-fixtures/reference"))
      .withPropertyName("differentialReferences")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    // The Vega-Lite fixtures and their two references, for the same reason.
    inputs
      .dir(rootProject.layout.projectDirectory.dir("test-fixtures/vega-lite"))
      .withPropertyName("vegaLiteFixtures")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
      .dir(rootProject.layout.projectDirectory.dir("test-fixtures/vega-lite-reference"))
      .withPropertyName("vegaLiteReferences")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    // Goldens are only rewritten when explicitly requested; see :updateGoldens.
    systemProperty(
      "vega.updateGoldens",
      providers.gradleProperty("updateGoldens").getOrElse("false"),
    )
    testLogging { showStandardStreams = false }
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
