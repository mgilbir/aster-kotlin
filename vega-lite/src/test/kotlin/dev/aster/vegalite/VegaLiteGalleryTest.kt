package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * **Every example Vega-Lite ships** — all 627 of them — compiled here and compared with what
 * upstream's own compiler emits, property by property.
 *
 * This existed once as a *measurement* and not as a gate. The sweep took the corpus from 124 of 627
 * matching to all 627, cause by ranked cause, and then nothing kept it there: the examples were not
 * checked in, no script referenced them, and no CI job ran them. The largest surface this project
 * had ever verified was protected by nothing, and a regression in any of the 503 examples that were
 * fixed would have been invisible until somebody swept again by hand.
 *
 * **Nothing is checked in, and that is the point.** The examples come from the source tarball at
 * the *pinned* version — the same version `oracle-js` installs — so a version bump re-sweeps
 * against that version's examples rather than against a stale copy somebody remembered to update.
 * 627 files of upstream's test data are also not this repository's to carry.
 *
 * The consequence is the one `scripts/vega-lite-oracle.sh` records in its own header: a corpus that
 * is not on disk must make this suite **fail**, not skip. A suite that assumes itself away when its
 * inputs are missing is the vacuous-test failure this repository keeps finding — most recently when
 * an `assumeTrue` skipped 195 of 198 fixtures and CI caught it only by counting. So there is no
 * `assumeTrue` here: [the corpus test][`the whole gallery is present and compiled by upstream`]
 * asserts the count, and `scripts/vega-lite-gallery.sh` is what arms it.
 *
 * **The specification, not the picture.** Compiling is a pure function of the specification, so all
 * 627 can go through both compilers without fetching a single byte of the datasets they name —
 * which is what makes a corpus this size affordable at all. Whether the chart then *draws* the same
 * is `VegaLiteFixtureDifferentialTest`'s question, on the 283 fixtures that carry data.
 */
class VegaLiteGalleryTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  fun `compiles to the specification upstream compiles it to`(name: String) {
    val ours =
      requireNotNull(VegaLiteCompiler().compileJson(specFile(name).readText()).vega) {
        "$name produced no specification at all"
      }
    val differences = SpecDiff.compare(VegaJson.parse(referenceFile(name).readText()), ours)
    assertTrue(
      differences.isEmpty(),
      buildString {
        append("$name: ${differences.size} difference(s) from upstream's compiler\n")
        differences.take(40).forEach { append("  ").append(it).append('\n') }
        if (differences.size > 40) append("  ... ${differences.size - 40} more\n")
      },
    )
  }

  /**
   * The corpus is there, upstream compiled all of it, and it has not shrunk.
   *
   * Three things at once because they are three ways for the gate above to pass over nothing: an
   * unarmed corpus, an upstream that refused some of it, and a corpus that quietly got smaller. The
   * last is a floor rather than an equality — a Vega-Lite release may add examples, and this should
   * sweep those too without a number here needing an edit — but it may not go down.
   */
  @Test
  fun `the whole gallery is present and compiled by upstream`() {
    assertTrue(
      manifestFile.isFile,
      "the gallery corpus is not built: no $manifestFile. Run scripts/vega-lite-gallery.sh, " +
        "which check.sh does for you. This is deliberately a failure rather than a skip: a suite " +
        "that assumes itself away when its inputs are missing reports success for a gate that " +
        "never ran.",
    )
    val manifest = VegaJson.parse(manifestFile.readText()) as VegaValue.Obj
    fun count(key: String) = (manifest.fields[key] as? VegaValue.Num)?.value?.toInt() ?: -1

    assertEquals(
      emptyList<String>(),
      (manifest.fields["failedUpstream"] as? VegaValue.Arr)?.values.orEmpty().map { it.toString() },
      "upstream's own compiler refused one of its own examples, so this sweep is comparing " +
        "against a corpus smaller than the one it reports",
    )
    assertTrue(
      count("examples") >= GALLERY_SIZE,
      "only ${count("examples")} example(s) in the gallery, down from $GALLERY_SIZE; the corpus " +
        "has shrunk and the sweep is passing over less than it used to",
    )
    assertEquals(
      count("examples"),
      count("compiled"),
      "upstream compiled fewer examples than the gallery holds",
    )
    assertEquals(
      count("examples"),
      examples().size,
      "the sweep compared a different number of examples than upstream compiled",
    )
  }

  /**
   * The examples came from the **same** Vega-Lite the references did.
   *
   * A sweep of one version's examples against another version's compiler is not a comparison, it is
   * a diff of two releases wearing this engine's name. The script derives the tag from the
   * installed package for exactly this reason; this is what stops the two drifting if it ever
   * stops.
   */
  @Test
  fun `the gallery and the compiler it is compared against are the same version`() {
    assertTrue(manifestFile.isFile, "the gallery corpus is not built; see the test above")
    val manifest = VegaJson.parse(manifestFile.readText()) as VegaValue.Obj
    val swept = (manifest.fields["vegaLiteVersion"] as? VegaValue.Str)?.value
    val installed =
      Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
        .find(File(repositoryRoot, "oracle-js/node_modules/vega-lite/package.json").readText())
        ?.groupValues
        ?.get(1)
    assertEquals(
      installed,
      swept,
      "the gallery examples and the compiler that produced the references are different versions " +
        "of Vega-Lite",
    )
  }

  companion object {
    private val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile

    /**
     * How many examples the pinned Vega-Lite ships, as a floor.
     *
     * 627 at 6.4.3. Named here rather than left implicit so that a corpus which fails to extract —
     * an empty directory, a tarball layout that moved — is a failure and not a silent pass over
     * nothing.
     */
    const val GALLERY_SIZE: Int = 627

    private val galleryDir = File(repositoryRoot, "build/vega-lite-upstream/examples/specs")

    private val referenceDir = File(repositoryRoot, "build/vega-lite-gallery")

    private val manifestFile = File(referenceDir, "manifest.json")

    private fun specFile(name: String) = File(galleryDir, "$name.vl.json")

    private fun referenceFile(name: String) = File(referenceDir, "$name.vega.json")

    /**
     * Every example upstream compiled, by name.
     *
     * Driven by the **references** rather than by the specification directory, so an example
     * upstream refused cannot appear here as a case that quietly passes; the corpus test above is
     * what notices that it is missing.
     */
    @JvmStatic
    fun examples(): List<String> =
      referenceDir
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".vega.json") }
        .map { it.name.removeSuffix(".vega.json") }
        .sorted()
        // JUnit's own answer to an empty source is a `TemplateInvocationValidationException` that
        // says "you must configure at least one set of arguments" — a true statement about JUnit
        // and nothing at all about what is wrong. This is the sentence a reader needs instead.
        .also {
          require(it.isNotEmpty()) {
            "The Vega-Lite gallery corpus is not built: $referenceDir holds no compiled example. " +
              "Run scripts/vega-lite-gallery.sh, which scripts/check.sh does for you."
          }
        }
  }
}
