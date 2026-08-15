pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // TEMPORARY, and the one thing blocking this branch from merging. `io.github.mgilbir:ktecma262`
    // 0.1.2 *is* on Maven Central now, but it publishes only the `jvm` and `js` variants — this core
    // also compiles for macosArm64, iosArm64, iosSimulatorArm64 and linuxX64, and there are no klibs
    // for those. So the engine is built and published locally with the four native targets added.
    //
    // **Ahead of mavenCentral() on purpose.** Gradle takes the first repository that has the
    // coordinate, and Central's 0.1.2 would otherwise shadow the local one — same version string,
    // fewer variants, and the native compiles fail with "No matching variant". Publishing a version
    // that carries the native targets removes this whole block.
    mavenLocal()
    google()
    mavenCentral()
  }
}

rootProject.name = "aster-kotlin"

// Platform-independent core. No Android types may appear in these modules.
include(":vega-model")

include(":vega-expression")

include(":vega-dataflow")

include(":vega-scene")

include(":vega-runtime")

include(":vega-svg")

include(":vega-loader")

include(":test-fixtures")

// Android presentation layer.
include(":vega-android-canvas")

include(":vega-compose")

include(":demo")

include(":benchmark")
