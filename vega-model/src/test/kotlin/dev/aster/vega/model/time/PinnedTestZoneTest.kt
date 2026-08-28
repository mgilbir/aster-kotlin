package dev.aster.vega.model.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

/**
 * The JVM tests run in the zone the build says they run in.
 *
 * A `time` scale is local, so the zone is an input to a golden rather than a detail of the machine.
 * The root build file pins `Europe/Amsterdam` and `scripts/oracle.sh` exports the same zone to
 * Node, so a reference generated on a laptop and one generated on CI are comparable by
 * construction. This asserts the pin *arrived* — a build script can state a pin but cannot observe
 * what the test process actually got, and that gap is not hypothetical: the native test tasks were
 * never covered by it at all, because `tasks.withType<Test>()` is Gradle's JVM test task and a
 * `KotlinNativeTest` is a sibling of it rather than a subtype.
 *
 * ### Why this is `jvmTest` and not `commonTest`
 *
 * Because the pin it checks is a JVM one, and deliberately so. Everything that depends on the zone
 * — the references, the oracle comparisons, the Node process — runs here. The native tasks are
 * pinned to **UTC** instead, on purpose, so that a `commonTest` suite reading the ambient zone by
 * accident fails somewhere; see the comment beside `KotlinNativeTest` in the root build file. A
 * common version of this test would have to assert two different things on two platforms, which is
 * a test describing the build rather than checking it.
 */
class PinnedTestZoneTest {

  /**
   * The zone is checked by its **rules**, not by its name.
   *
   * `Europe/Amsterdam` has been a *link* to `Europe/Brussels` since tzdata 2022b, and whether a
   * platform reports the name it was asked for or the canonical one it resolves to is the
   * platform's business. An id comparison can therefore fail on a host where the pin worked
   * perfectly — a check reporting on its own implementation rather than on the thing it guards.
   *
   * The offsets are what every zone-dependent golden actually depends on, and the two names share
   * them exactly, being the same zone. Two instants rather than one, because a single winter
   * reading is also what a fixed UTC+1 would give: it is the *pair* that identifies a zone with
   * summer time, and summer time is where a local time scale actually breaks.
   */
  @Test
  fun `the ambient zone has the pinned zone's rules`() {
    val zone = TimeZone.currentSystemDefault()
    val winter = zone.offsetAt(Instant.parse("2024-01-15T12:00:00Z")).totalSeconds
    val summer = zone.offsetAt(Instant.parse("2024-07-15T12:00:00Z")).totalSeconds
    assertEquals(
      listOf(3600, 7200),
      listOf(winter, summer),
      "the tests must run in the pinned zone; got ${zone.id}. See TEST_TIME_ZONE in the root build " +
        "file — and note that a Gradle `Test` task is the only thing that pin reaches.",
    )
  }
}
