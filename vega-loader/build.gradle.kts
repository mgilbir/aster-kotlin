plugins { alias(libs.plugins.kotlin.jvm) }

dependencies { api(project(":vega-runtime")) }

// A Kotlin Multiplatform module gets its publications from the plugin; a plain JVM one does not, so
// the component is named here. Central also wants sources, which KMP would have produced.
java { withSourcesJar() }

publishing { publications { register<MavenPublication>("maven") { from(components["java"]) } } }
