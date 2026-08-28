package dev.aster.vega.model.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

/**
 * The tests run in the zone the build says they run in — on **every** target.
 *
 * A `time` scale is local, so the zone is an input to a golden rather than a detail of the machine,
 * and the root build file pins `Europe/Amsterdam` so that a reference generated on a laptop and one
 * generated on CI are comparable. `scripts/oracle.sh` exports the same zone to Node for the same
 * reason.
 *
 * What this asserts is that the pin *arrived*. It did not, for the native targets, for as long as
 * they have existed: `tasks.withType<Test>()` is Gradle's JVM test task and a `KotlinNativeTest` is
 * a sibling of it rather than a subtype, so `linuxX64Test` and `macosArm64Test` ran in the host's
 * own zone — Amsterdam on a laptop, UTC on both runners — and nothing said so. No suite happened to
 * depend on it, so nothing failed; the first one that did would have failed on one host only, and
 * read as a rendering bug rather than as a harness bug.
 *
 * It lives here rather than beside the build because a build script cannot assert what a test
 * process actually got, which is the whole distinction that went unnoticed.
 */
class PinnedTestZoneTest {

  @Test
  fun `the ambient zone is the one the build pins`() {
    assertEquals(
      "Europe/Amsterdam",
      TimeZone.currentSystemDefault().id,
      "the tests must run in the pinned zone; see TEST_TIME_ZONE in the root build file",
    )
  }
}
