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
    google()
    mavenCentral()
    // TEMPORARY, and the one thing blocking this branch from merging: `io.github.mgilbir:ktecma262`
    // is not on Maven Central yet — the namespace and signing key are still being set up — so it is
    // consumed from a local `publishToMavenLocal`. That breaks the rule every other coordinate here
    // follows (README: pinned to a release verified to exist in google() or mavenCentral()) and it
    // breaks any clone that has not built the engine first. Remove this line the day 0.1.2 is
    // published.
    mavenLocal()
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
