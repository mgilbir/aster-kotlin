import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.PathSensitivity
import org.gradle.plugins.signing.Sign
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Modules that are libraries someone consumes, and so publish and carry an ABI dump.
 *
 * `demo` is an app, `benchmark` is a harness and `test-fixtures` is scaffolding consumed by tests;
 * none of the three is anybody's dependency.
 */
val publishable =
  setOf(
    "vega-model",
    "vega-expression",
    "vega-dataflow",
    "vega-scene",
    "vega-svg",
    "vega-runtime",
    "vega-lite",
    "vega-loader",
    "vega-compose-multiplatform",
    "vega-compose",
    "vega-android-canvas",
  )

/**
 * Turns on ABI validation and points its dump at `api/`, for a module someone depends on.
 *
 * Eleven modules are published, every one applies `explicitApi()`, and nothing made a change to
 * what they expose *visible*: a removed function or a widened signature reached a consumer's build
 * rather than this repository's diff. `checkKotlinAbi` runs as part of `check`; `./gradlew
 * updateKotlinAbi` rewrites the dumps, and that diff is reviewed as a change to the surface other
 * people compile against.
 *
 * Kotlin's own ABI validation rather than the `binary-compatibility-validator` plugin an external
 * review asked for, which writes the same `.api` format. Two reasons. It covers the **klib** ABI of
 * the native targets as well as the JVM one, and a klib is what an iOS consumer links against. And
 * the plugin keys off the Kotlin plugin ids, which `vega-compose` and `vega-android-canvas` do not
 * apply: since AGP 9 their Kotlin support comes from `KotlinBaseApiPlugin`, applied by the Android
 * plugin itself, so the plugin creates no tasks for them at all.
 *
 * **Those two are not covered by this either, and the reason is worth writing down.** Kotlin's ABI
 * validation reads a module's Maven publications, which for an Android library it does not support
 * — KGP even has a diagnostic named for it, `AbiValidationAndroidPublicationNotSupported` — and
 * pointing it at the main compilation instead leaves a provider with no value.
 *
 * They are covered by `scripts/android-api.sh` instead, which snapshots what `javap` reads off the
 * compiled classes, the way `scripts/foreign-api.sh` snapshots the Obj-C surface and for the same
 * reason. This used to say their surface was small enough for a consumer to read the diff by hand.
 * Reading it by hand is what failed: `vega-compose` shipped 0.2.0 exposing a controller, a modifier
 * and one callback while the view underneath had three more seams, and an adopter found it (#99)
 * rather than a review. A surface nobody snapshots is a surface nobody diffs.
 *
 * The API is `@ExperimentalAbiValidation` and says so. The tasks are `checkKotlinAbi` and
 * `updateKotlinAbi`; the dumps land under each module's `api/`. The `*LegacyAbi` names are the same
 * tasks under their first spelling and warn that they are deprecated — Kotlin renamed them once the
 * feature stopped being about the *legacy* `binary-compatibility-validator` format.
 */
@OptIn(ExperimentalAbiValidation::class)
fun KotlinMultiplatformExtension.enableAbiDump() {
  // Calling it is what turns it on: in Kotlin 2.4 the `enabled` property and the `klib { }` block
  // are
  // both gone, and a klib dump is produced for every klib target there is.
  abiValidation()
}

@OptIn(ExperimentalAbiValidation::class)
fun KotlinJvmProjectExtension.enableAbiDump() {
  abiValidation()
}

/**
 * The bytecode level every publishable module is compiled to, and the class file major it produces.
 *
 * One constant for the two, because the pair is the whole point: `JVM_17` is what the compilers are
 * told and 61 is what a consumer's `javap` reads back, and a release whose artifacts disagree about
 * it is a release with two toolchain requirements in it. See `checkBytecodeLevel`.
 */
val PUBLISHED_JVM_TARGET = JvmTarget.JVM_17
val PUBLISHED_CLASS_FILE_MAJOR = 61

/**
 * Asserts that every class file a publishable module compiles is at [PUBLISHED_CLASS_FILE_MAJOR].
 *
 * The pin itself is one line per plugin below; this is what makes it *checkable*. Version 0.1.0
 * shipped its jars at 61 and its Android AAR at 65 because the pin named a Kotlin **target type**
 * and the AGP Kotlin Multiplatform Android target is not one of those, so the AAR silently took the
 * level of whichever JDK cut the release. Nothing in the build said so and nothing could: the level
 * a consumer must reach was a property of the release machine.
 *
 * So the level is asserted against the bytes rather than against the configuration. Reading the
 * class files is the only check that cannot be fooled by a target nobody enumerated — which is the
 * exact failure this replaces.
 *
 * [directories] must not be empty and each one must contain a class, so the gate cannot pass having
 * looked at nothing (PROJECT_BRIEF.md 18).
 */
abstract class CheckBytecodeLevel : DefaultTask() {
  @get:InputFiles abstract val directories: ConfigurableFileCollection

  @get:Input abstract val expectedMajor: Property<Int>

  @TaskAction
  fun check() {
    val expected = expectedMajor.get()
    val wrong = mutableListOf<String>()
    val empty = mutableListOf<String>()
    var counted = 0
    for (directory in directories.files) {
      if (!directory.isDirectory) continue
      var inDirectory = 0
      directory
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .forEach { file ->
          inDirectory++
          counted++
          // Bytes 6 and 7 of a class file are the major version, big-endian, straight after the
          // magic and the minor. Read rather than parsed: nothing else in the file is wanted.
          val header = file.inputStream().use { stream -> ByteArray(8).also { stream.read(it) } }
          val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
          if (major != expected) wrong += "$file is class file major $major"
        }
      if (inDirectory == 0) empty += directory.toString()
    }
    if (counted == 0) {
      throw GradleException(
        "checkBytecodeLevel found no class files at all, so it has checked nothing. " +
          "Its inputs are ${directories.files.size} directories; run it through `assemble` " +
          "rather than on its own."
      )
    }
    if (empty.isNotEmpty()) {
      logger.lifecycle(
        "note: ${empty.size} compilation output directories held no classes " +
          "(${empty.joinToString(", ")}); a compilation with no sources is expected, one that " +
          "was skipped is not."
      )
    }
    if (wrong.isNotEmpty()) {
      throw GradleException(
        "A publishable module compiled to a bytecode level other than $expected:\n" +
          wrong.take(20).joinToString("\n") { "  $it" } +
          (if (wrong.size > 20) "\n  … and ${wrong.size - 20} more" else "") +
          "\n\nOne release has one level. Pin the compile that produced these — see " +
          "PUBLISHED_JVM_TARGET in the root build file — rather than raising the expectation."
      )
    }
    logger.lifecycle("==> $counted published class files, all at major $expected")
  }
}

val checkBytecodeLevel =
  tasks.register<CheckBytecodeLevel>("checkBytecodeLevel") {
    group = "verification"
    description = "Asserts every publishable module's class files are at one bytecode level."
    expectedMajor.set(PUBLISHED_CLASS_FILE_MAJOR)
  }

/**
 * The zone every test runs in.
 *
 * Europe/Amsterdam, because it observes daylight saving and is one hour off UTC in winter — so a
 * bug that only shows up away from UTC, or only across a transition, shows up here.
 */
val TEST_TIME_ZONE = "Europe/Amsterdam"

/**
 * The version every publishable module carries, and the one a release tag has to match.
 *
 * One place, because eleven modules publishing at eleven versions is not a release. The release
 * workflow reads this line, so its shape matters: `version = "x.y.z"` at the top level.
 */
version = "0.4.0"

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spotless)
}

allprojects {
  apply(plugin = "com.diffplug.spotless")

  extensions.configure<SpotlessExtension> {
    kotlin {
      // No `targetExclude("**/build/**")`. It looks like belt and braces over a target that is
      // already anchored at `src/`, and it is the opposite: Spotless implements an exclusion as a
      // `SubtractingFileCollection`, which has to *enumerate* the files it subtracts, so the
      // pattern makes Gradle walk `build/` — the one directory the exclusion exists to avoid.
      //
      // On macOS that directory holds a linked framework, and a framework is a versioned bundle:
      // `AsterVega.framework/AsterVega -> Versions/Current/AsterVega`. `./gradlew build` links it
      // and runs Spotless concurrently, and a walk arriving mid-link finds a symlink whose target
      // does not exist yet: "Couldn't follow symbolic link", and the build fails. That is what the
      // 0.2.0 release died of, in the one workflow that runs `build` rather than `check.sh` and on
      // the one host that compiles Apple targets.
      //
      // `src/**/*.kt` is relative to the project directory, so nothing under `build/` can match it
      // and nothing is lost. `SpotlessSymlinkTest` fails if the exclusion comes back.
      target("src/**/*.kt")
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
      if (name in publishable) enableAbiDump()
      // Bytecode level, not a toolchain: the same JDK builds everything, and 17 is what the Android
      // modules consume. Asking for a 17 *toolchain* would demand a second JDK on every machine
      // that builds this for no gain.
      //
      // Every task that emits JVM bytecode, **not** `targets.withType<KotlinJvmTarget>()`. The AGP
      // Kotlin Multiplatform Android target is not a `KotlinJvmTarget`, so `withType` never saw it
      // and its compile took the level of whichever JDK cut the release. Measured on 0.1.0: every
      // `*-jvm-0.1.0.jar` is class file major 61 and `vega-compose-multiplatform.aar` is 65, which
      // is a release with two levels in it and only one of them written down. A consumer reading
      // the jars concludes 17 and is right until an Android compilation resolves the AAR, and then
      // a Robolectric test on a JDK 17 runtime dies with `UnsupportedClassVersionError` at the
      // first composable it reaches. Selecting on the *task* covers the Android target, any target
      // AGP adds later, and the `jvm` one this used to name — one release, one level, by
      // construction rather than by enumeration.
      tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(PUBLISHED_JVM_TARGET)
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
        jvmTarget.set(PUBLISHED_JVM_TARGET)
        allWarningsAsErrors.set(true)
      }
      explicitApi()
      if (name in publishable) enableAbiDump()
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

/**
 * Hands each publishable module's compiled classes to the root `checkBytecodeLevel`.
 *
 * Selected by **task**, exactly as the pin above is, and for the same reason: the compilation that
 * shipped 0.1.0 at the wrong level was one nobody had enumerated. Test compilations are left out —
 * they are not published, and depending on them would make a verification task build the whole test
 * tree.
 */
subprojects {
  if (name in publishable) {
    val compilations = tasks.withType<KotlinJvmCompile>().matching { !it.name.contains("Test") }
    checkBytecodeLevel.configure { directories.from(compilations.map { it.destinationDirectory }) }
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
    // The canonical draw-call goldens, which `SceneWalkGoldenTest` reads and the Swift package's
    // `SceneWalkParityTests` reads too — one file compared by two walks, so a stale task here is a
    // parity check that did not run.
    inputs
      .dir(rootProject.layout.projectDirectory.dir("test-fixtures/scene-walk"))
      .withPropertyName("sceneWalkGoldens")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    // The cross-host conformance goldens, read by this module's readers and by the Android and
    // SwiftUI ones. Same reason again, and it is the reason found the hard way: a broken
    // `placement.txt` was committed and `jvmTest` reported up to date, so the suite whose whole
    // purpose is to catch a host disagreeing said nothing.
    inputs
      .dir(rootProject.layout.projectDirectory.dir("test-fixtures/host-conformance"))
      .withPropertyName("hostConformanceGoldens")
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
  description =
    "Regenerates scene-snapshot, SVG and draw-call goldens. Review the diff as a rendering change."
  doFirst {
    throw GradleException(
      "Run as: ./gradlew test -PupdateGoldens=true --rerun-tasks (see PROJECT_BRIEF.md 18.3)"
    )
  }
}

// ----------------------------------------------------------------- publishing

/**
 * Publishing, applied to every module that is a library rather than an app or a fixture.
 *
 * The shape here is `ktecma262`'s, and it is that shape because of two releases that went wrong
 * there. Both failures were silent — the upload succeeded and the artifacts were immutable on
 * Central before anyone noticed — so the checks that came out of them are the point of this file
 * rather than an embellishment on it.
 *
 * What differs is that this project has **eleven** publishable modules where that one had a single
 * module with several targets. So the bundle is assembled across projects, and both checks run per
 * project: a module missing from the bundle is exactly the failure mode being guarded against, and
 * eleven modules is eleven chances to hit it.
 */

/** Build-relative directory the Central Portal bundle is staged in, under the root project. */
val centralBundleDir = "central-bundle"

/** What each module is, for its POM. A description that says "part of Aster Vega" says nothing. */
val descriptions =
  mapOf(
    "vega-model" to
      "The Vega specification as Kotlin types, with the JSON value model the whole engine reads.",
    "vega-expression" to
      "Vega's expression language: parser and evaluator for the expressions a specification writes.",
    "vega-dataflow" to
      "Vega's dataflow: the transforms, scales, geographic projections and layouts a chart derives.",
    "vega-scene" to
      "The scene graph a compiled chart becomes — marks, paints, transforms and text layout.",
    "vega-svg" to
      "Serialises a compiled Aster Vega scene to SVG, the way Vega's own renderer does.",
    "vega-runtime" to
      "Compiles a Vega specification to a scene, and runs the interactions it declares.",
    "vega-lite" to
      "Compiles Vega-Lite to Vega in Kotlin, checked property by property against upstream's own " +
        "compiler.",
    "vega-loader" to "Loads a specification's data from the filesystem or the network, on the JVM.",
    "vega-compose-multiplatform" to
      "Draws an Aster Vega scene with Compose Multiplatform, on Android, iOS and the desktop.",
    "vega-compose" to "Draws an Aster Vega scene with Jetpack Compose on Android.",
    "vega-android-canvas" to
      "Draws an Aster Vega scene straight onto an Android Canvas, with accessibility nodes.",
  )

configure(subprojects.filter { it.name in publishable }) {
  apply(plugin = "maven-publish")
  apply(plugin = "signing")

  // Captured, because inside the publication and repository blocks below the receiver is no longer
  // the project.
  val moduleName = name
  val bundleTree = java.io.File(rootProject.layout.buildDirectory.get().asFile, centralBundleDir)

  group = "io.github.mgilbir.astervega"
  version = rootProject.version

  // Maven Central requires a javadoc artifact. The API documentation is KDoc on the source, which
  // the sources jar already carries, so this is a placeholder rather than a second copy of it.
  val javadocJar by tasks.registering(Jar::class) { archiveClassifier.set("javadoc") }

  extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
      artifact(javadocJar)
      pom {
        name.set(moduleName)
        description.set(
          descriptions[moduleName]
            ?: error("no POM description for $moduleName; add one to `descriptions` above")
        )
        url.set("https://github.com/mgilbir/aster-kotlin")
        licenses {
          license {
            // BSD 3-Clause, because this is a port of Vega and a port is a derivative work.
            name.set("BSD 3-Clause License")
            url.set("https://github.com/mgilbir/aster-kotlin/blob/main/LICENSE")
            distribution.set("repo")
          }
        }
        developers {
          developer {
            id.set("mgilbir")
            name.set("Miguel Eduardo Gil Biraud")
            url.set("https://github.com/mgilbir")
          }
        }
        scm {
          url.set("https://github.com/mgilbir/aster-kotlin")
          connection.set("scm:git:https://github.com/mgilbir/aster-kotlin.git")
          developerConnection.set("scm:git:ssh://git@github.com/mgilbir/aster-kotlin.git")
        }
      }
    }

    repositories {
      // A local directory under the **root** project, not a remote server, and shared by every
      // module so that one bundle holds the whole release.
      //
      // Uploading publications separately to the staging API means the server assembles the
      // deployment from whatever it believes arrived. In `ktecma262` 0.1.3 it assembled four
      // modules out of seven: the ones it dropped uploaded successfully and were never reported as
      // failures. There is no way to see that from the upload side. So publish into a tree, zip it,
      // and hand the Portal one bundle containing exactly what this build produced.
      maven {
        name = "centralBundle"
        url = bundleTree.toURI()
      }
    }
  }

  extensions.configure<SigningExtension> {
    // Required by Central, irrelevant locally, so it switches itself on only when a key is given.
    // An ASCII-armoured private key straight from the environment: never written to disk, never a
    // Gradle property.
    val key = providers.environmentVariable("MAVEN_GPG_PRIVATE_KEY").orNull
    val password = providers.environmentVariable("MAVEN_GPG_PASSPHRASE").orNull?.trim()
    if (!key.isNullOrBlank()) {
      useInMemoryPgpKeys(key, password)
      sign(extensions.getByType<PublishingExtension>().publications)
    }
  }

  // Gradle cannot infer that each publication's signing task needs the shared javadoc jar first.
  tasks.withType<AbstractPublishToMaven>().configureEach { dependsOn(tasks.withType<Sign>()) }

  /**
   * Fails when a declared target would not actually be published.
   *
   * The root module lists a variant for every *declared* target whichever host built it, but Kotlin
   * creates a publication only for the targets that host can compile — Apple targets need a Mac. So
   * publishing from anywhere else uploads a root module pointing at artifacts that were never
   * built, and nothing goes red until a consumer tries to resolve the dependency. That is how
   * `ktecma262` 0.1.2 shipped: from Linux, with no native variants at all, immutable before anyone
   * noticed.
   *
   * **Nothing is looked up while the task is registered.** An earlier attempt asked for the Kotlin
   * and publishing extensions here and failed configuration outright: the root project configures
   * this block before a module has applied its own plugins, and `vega-compose-multiplatform` uses
   * the Android multiplatform plugin, whose extensions are not these types even afterwards. Both
   * sets are read in `afterEvaluate` into properties the task resolves at execution instead, so the
   * check adapts to whatever a module turns out to be rather than assuming.
   *
   * Not wired into `check`: on any host but macOS it is *expected* to fail.
   */
  val declaredTargets = objects.setProperty(String::class.java)
  val publicationNames = objects.setProperty(String::class.java)
  afterEvaluate {
    (extensions.findByName("kotlin") as? KotlinMultiplatformExtension)?.let { kmp ->
      declaredTargets.set(kmp.targets.names.toSortedSet())
    }
    extensions.findByType<PublishingExtension>()?.let { published ->
      publicationNames.set(published.publications.names.toSortedSet())
    }
  }

  val moduleMetadata = layout.buildDirectory.file("publications/kotlinMultiplatform/module.json")
  val label = path

  tasks.register("verifyPublishedVariants") {
    group = "verification"
    description = "Check every declared target of this module will really be published"
    // Matching rather than naming it: a module with no multiplatform metadata has no such task, and
    // asking for one by name would fail configuration on exactly the modules this skips.
    dependsOn(tasks.matching { it.name == "generateMetadataFileForKotlinMultiplatformPublication" })

    doLast {
      val declared = declaredTargets.get()
      if (declared.isEmpty()) {
        logger.lifecycle("$label: not a plain multiplatform module; nothing to check")
        return@doLast
      }
      // The common target's publication is named for the plugin, not the target.
      val expected = declared.map { if (it == "metadata") "kotlinMultiplatform" else it }
      val present = publicationNames.get()
      check(present.isNotEmpty()) { "$label: no publications at all — the check would be vacuous" }

      val missing = expected.filterNot { it in present }
      check(missing.isEmpty()) {
        buildString {
          appendLine("$label: no publication for ${missing.joinToString(", ")}")
          appendLine("Declared targets: ${declared.joinToString(", ")}")
          appendLine("Publications:     ${present.joinToString(", ")}")
          append("Apple targets require a macOS host.")
        }
      }

      // Non-vacuity: the root module has to carry variants for them, not merely name publications.
      val json = moduleMetadata.get().asFile
      check(json.isFile) { "$label: no module metadata at ${json.path}" }

      @Suppress("UNCHECKED_CAST")
      val parsed = groovy.json.JsonSlurper().parse(json) as Map<String, Any?>

      @Suppress("UNCHECKED_CAST")
      val variants = (parsed["variants"] as? List<Map<String, Any?>>).orEmpty()
      val names = variants.mapNotNull { it["name"] as? String }
      val unlisted =
        declared
          .filter { it != "metadata" }
          .filter { target -> names.none { it.startsWith(target) } }
      check(unlisted.isEmpty()) {
        "$label: the root module has no variant for ${unlisted.joinToString(", ")}"
      }
      logger.lifecycle("$label: ${declared.size} declared targets, ${names.size} variants")
    }
  }
}

/**
 * The modules the convention publishes. Kept in step by `verifyCentralBundle` failing otherwise.
 */
val publishedModules =
  subprojects.filter { it.plugins.hasPlugin("maven-publish") }.map { it.name }.sorted()

/**
 * The staging directory accumulates, so a previous version's files would ride along in the next
 * bundle. Cleared before anything publishes into it.
 */
val cleanCentralBundle by
  tasks.registering(Delete::class) {
    delete(java.io.File(layout.buildDirectory.get().asFile, centralBundleDir))
  }

/**
 * The single zip uploaded to the Central Portal.
 *
 * One bundle per deployment, laid out as a Maven repository, so what is uploaded is what is
 * published — there is no server-side assembly step that can quietly leave a module out.
 */
// Each publish task waits for the clean, rather than the zip doing so. Hung off the zip, with
// `org.gradle.parallel=true` in gradle.properties, the delete races the tasks filling the tree — it
// failed intermittently exactly that way before this was moved.
subprojects
  .filter { it.plugins.hasPlugin("maven-publish") }
  .forEach { module ->
    module.tasks.withType<PublishToMavenRepository>().configureEach {
      if (name.endsWith("ToCentralBundleRepository")) dependsOn(cleanCentralBundle)
    }
  }

val centralBundle by
  tasks.registering(Zip::class) {
    group = "publishing"
    description = "Build the Maven Central Portal upload bundle for every published module"
    subprojects
      .filter { it.plugins.hasPlugin("maven-publish") }
      .forEach { dependsOn("${it.path}:publishAllPublicationsToCentralBundleRepository") }
    from(java.io.File(layout.buildDirectory.get().asFile, centralBundleDir))
    archiveFileName.set("aster-kotlin-$version-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  }

/**
 * Checks the bundle before it is uploaded.
 *
 * Every module has to be present, each with a POM and Gradle module metadata, and — when signing is
 * on — a detached signature beside each file. A module missing here is a module the Portal would
 * have published without it, which is the failure this whole arrangement exists to prevent.
 */
val verifyCentralBundle by tasks.registering {
  group = "verification"
  description = "Check the upload bundle holds every module, with metadata and signatures"
  dependsOn(centralBundle)
  val tree =
    java.io.File(
      layout.buildDirectory.get().asFile,
      "$centralBundleDir/io/github/mgilbir/astervega",
    )
  val expected = publishedModules
  val signed =
    providers.environmentVariable("MAVEN_GPG_PRIVATE_KEY").map { it.isNotBlank() }.orElse(false)

  doLast {
    val root = tree
    check(root.isDirectory) { "no bundle tree at ${root.path}" }
    val present = root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
    check(present.isNotEmpty()) { "the bundle is empty — the check would pass vacuously" }

    // A module in the build but not in the bundle is the 0.1.3 failure, locally and before upload.
    //
    // Matched **exactly**, not by prefix. A Kotlin Multiplatform module publishes a root
    // `<name>` beside a `<name>-<target>` for each target, so a prefix test looks right — and
    // `vega-compose` then passes on the strength of `vega-compose-multiplatform` being there,
    // which is how this check first reported eleven modules present when nine were.
    val missing = expected.filterNot { module -> module in present }
    check(missing.isEmpty()) {
      "not in the bundle: ${missing.joinToString(", ")}\nPresent: ${present.joinToString(", ")}"
    }

    var files = 0
    for (module in present) {
      val versions = File(root, module).listFiles().orEmpty().filter { it.isDirectory }
      check(versions.isNotEmpty()) { "$module has no version directory" }
      for (dir in versions) {
        val names = dir.listFiles().orEmpty().map { it.name }
        files += names.size
        fun requireOne(suffix: String) =
          check(names.any { it.endsWith(suffix) }) { "$module/${dir.name}: no *$suffix" }
        requireOne(".pom")
        requireOne(".module")
        if (signed.get()) {
          requireOne(".pom.asc")
          requireOne(".module.asc")
        }
      }
    }
    logger.lifecycle(
      "bundle holds ${present.size} modules, $files files" +
        if (signed.get()) ", signed" else " (unsigned — no signing key configured)"
    )
  }
}
