plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.serialization.json)
  // Calendar arithmetic for dates. JetBrains' recommendation for shared Kotlin Multiplatform code,
  // which is what keeps the core off java.time.
  api(libs.kotlinx.datetime)
}
