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
  // macOS stays out of it deliberately: the Swift package's tests point straight at
  // `bin/macosArm64/debugFramework`, and adding a platform to an XCFramework does not change that
  // path but does make the assembly task slower for every iOS build.
  val xcframework = XCFramework("AsterVega")

  listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "AsterVega"
      isStatic = true
      export(project(":vega-model"))
      export(project(":vega-scene"))
      export(project(":vega-expression"))
      export(project(":vega-dataflow"))
      if (target.name != "macosArm64") xcframework.add(this)
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
        // Only so FixtureSvgTest can write a lookable-at rendering of each fixture beside the
        // oracle's.
        implementation(project(":vega-svg"))
        implementation(libs.kotlinx.coroutines.test)
      }
    }
  }
}
