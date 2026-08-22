import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
  jvm()
  macosArm64()
  iosArm64()
  iosSimulatorArm64()
  linuxX64()

  // The framework Swift sees. A renderer written against CoreGraphics needs the whole vocabulary —
  // a compiled `Scene` and the nodes in it — so the modules that define them are `export`ed rather
  // than merely depended on; without that their types arrive as opaque handles Swift cannot read.
  //
  // Static, because a static framework needs no embedding step in the consuming app and cannot go
  // missing at launch. `baseName` is what `import AsterVega` refers to.
  // The iOS slices are also bundled into an **XCFramework**, which is what an Xcode project can
  // link
  // as one artifact: a device build and a simulator build of the same framework cannot sit in the
  // same
  // search path, and an app that has to pick between two directories by SDK is an app that links
  // the
  // wrong one eventually. `assembleAsterVegaDebugXCFramework` builds it; `scripts/ios-demo.sh` runs
  // that before xcodebuild.
  //
  // **macOS is in the release XCFramework and out of the debug one**, and the asymmetry is the
  // point.
  //
  // It used to be out of both, with the reason given here as speed: the Swift package's own tests
  // point
  // straight at `bin/macosArm64/debugFramework`, so nothing local needed a macOS slice and every
  // iOS
  // debug assembly would have paid for one. That reasoning holds for *development* and was wrong
  // for
  // what ships. The released `Package.swift` declares `platforms: [.macOS(.v13), .iOS(.v16)]` and
  // its
  // `AsterVegaRender` target imports `AsterVega`, so a consumer running `swift build` on a Mac —
  // which
  // is what a plain command-line build, an Xcode preview and a package-scheme hygiene gate all do —
  // got `no such module 'AsterVega'` from 0.1.0. The iOS slices were fine; the platform the
  // manifest
  // also promised had nothing behind it.
  //
  // So the debug XCFramework stays iOS-only, and `scripts/ios-demo.sh` keeps its fast path, while
  // the
  // release one carries all three slices. `release.yml` asserts the slice list before it tags.
  val xcframework = XCFramework("AsterVega")

  listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "AsterVega"
      isStatic = true
      export(project(":vega-model"))
      export(project(":vega-scene"))
      export(project(":vega-expression"))
      export(project(":vega-dataflow"))
      // The SVG serializer, so a foreign host can export a chart rather than only draw it. It is
      // multiplatform already and was simply not on this list, which is the only reason an iOS host
      // had
      // no export at all while Android had three formats.
      export(project(":vega-svg"))
      // The Vega-Lite compiler, so a foreign host can accept **either grammar** from a reader. It
      // was JVM-only until now for no reason but its build file — nothing in it touches the JVM —
      // which meant a specification pasted into the Android demo drew a chart and the same text
      // pasted into the iOS one could not be read at all. That is not a host restriction.
      export(project(":vega-lite"))
      // Debug: the two iOS slices, which is what the demo links. Release: those plus macOS, because
      // that is the artefact a consumer's manifest resolves and its platforms include macOS.
      if (
        target.name != "macosArm64" ||
          buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE
      ) {
        xcframework.add(this)
      }
    }
  }

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        api(project(":vega-model"))
        api(project(":vega-expression"))
        api(project(":vega-dataflow"))
        api(project(":vega-scene"))
        api(project(":vega-svg"))
        // `api` because it is exported to foreign hosts: `VegaLiteInput.toVega` is the entry point
        // a
        // host calls with text a reader supplied, so its types have to arrive readable and not as
        // opaque handles. One-directional still — `:vega-lite` knows nothing about the runtime.
        api(project(":vega-lite"))
        api(libs.kotlinx.coroutines.core)
      }
    }
    jvmTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies {
        // The loader and the fixtures are JVM scaffolding — a file on disk and a socket — which is
        // why they are the one pair of dependencies that does not follow the core off the JVM.
        implementation(project(":vega-loader"))
        implementation(project(":test-fixtures"))
        implementation(libs.kotlinx.coroutines.test)
      }
    }
  }
}
