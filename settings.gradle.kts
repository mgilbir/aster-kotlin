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

include(":test-fixtures")

// Android presentation layer.
include(":vega-android-canvas")

include(":vega-compose")

include(":demo")

include(":benchmark")
