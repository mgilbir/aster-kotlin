package dev.aster.vega.runtime

import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The three surfaces a specification can name that nothing was measuring: projections, time units,
 * and expression functions.
 *
 * Every other gate here runs **documented → code**: does this row's claim still hold, is the test
 * it cites still there, does the number match what is on disk. None of them runs **upstream →
 * code**, so a feature upstream has and this engine does not is invisible — there is no row to be
 * wrong, no citation to dangle, and no number to drift. Absence is what nobody reviews.
 *
 * Three coverage probes now close that for the guide properties, the Vega-Lite constructs and the
 * `config` blocks. These are the last three surfaces a specification can name by a word upstream
 * defines. All three were measured complete by hand before this test was written; what it adds is
 * that they *stay* complete without anybody measuring again.
 *
 * Each inventory is derived from the pinned install, never hand-listed, and each carries a guard on
 * its own count — a scrape that stopped matching would otherwise report perfect coverage of
 * nothing, which is the failure this repository keeps meeting.
 */
class UpstreamSurfaceCoverageTest {

  private val install = File("../oracle-js/node_modules")

  /**
   * Each measurement, for `docs/upstream-coverage.md` to render beside the other three.
   *
   * Written per kind as the kind is measured rather than collected at the end, because the three
   * tests are independent and JUnit may run any subset of them.
   */
  private fun record(kind: String, upstream: Int, accepted: Int, refused: List<String>) {
    val file = File("build/surface-coverage/$kind.json").apply { parentFile.mkdirs() }
    file.writeText(
      """{"kind": "$kind", "upstream": $upstream, "accepted": $accepted, """ +
        """"refused": [${refused.joinToString(", ") { "\"$it\"" }}]}""" +
        "\n"
    )
  }

  private fun pinned(path: String): File {
    val file = File(install, path)
    assumeTrue(file.exists(), "no pinned upstream at ${file.path}; run scripts/check.sh or npm ci")
    return file
  }

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  // ---- projections --------------------------------------------------------------

  /**
   * The keys of upstream's own projection table.
   *
   * Vega's schema does not describe `projection.type` — `mercator` appears nowhere in it — so the
   * list comes from `vega-projection`'s table, whose entries are `name: geoSomething,` at a fixed
   * indent.
   */
  private fun upstreamProjections(): List<String> =
    Regex("""^ {2}(\w+):\s+geo\w+,?$""", RegexOption.MULTILINE)
      .findAll(pinned("vega-projection/src/projections.js").readText())
      .map { it.groupValues[1] }
      .distinct()
      .sorted()
      .toList()

  @Test
  fun `every projection upstream registers is accepted`() {
    val all = upstreamProjections()
    assertTrue(
      all.size >= 15,
      "only ${all.size} projections scraped; the table's shape changed and this measures nothing",
    )
    val refused = all.filter { type ->
      compile(
          """
            {"width": 40, "height": 40, "padding": 0,
             "projections": [{"name": "p", "type": "$type"}],
             "data": [{"name": "t",
                       "values": [{"g": {"type": "Point", "coordinates": [0, 0]}}],
                       "transform": [{"type": "geopath", "field": "g", "projection": "p"}]}],
             "marks": [{"type": "path", "from": {"data": "t"},
                        "encode": {"enter": {"stroke": {"value": "#000000"}}}}]}
            """
            .trimIndent()
        )
        .diagnostics
        .any { type in it.message }
    }
    record("projection", all.size, all.size - refused.size, refused)
    assertTrue(refused.isEmpty(), "${refused.size} of ${all.size} projections refused: $refused")
  }

  // ---- time units ---------------------------------------------------------------

  /** The `timeunit` transform's own enum, which is where the schema does say. */
  private fun upstreamTimeUnits(): List<String> {
    val schema = VegaJson.parse(pinned("vega/build/vega-schema.json").readText()) as VegaValue.Obj
    val defs = schema.fields["definitions"] as VegaValue.Obj
    val transform = defs.fields["timeunitTransform"] as VegaValue.Obj
    val units = transform.fields["properties"] as VegaValue.Obj
    fun enums(value: VegaValue): List<String> =
      when (value) {
        is VegaValue.Obj ->
          if (value.fields["enum"] is VegaValue.Arr) {
            (value.fields["enum"] as VegaValue.Arr).values.mapNotNull {
              (it as? VegaValue.Str)?.value
            }
          } else {
            value.fields.values.flatMap { enums(it) }
          }
        is VegaValue.Arr -> value.values.flatMap { enums(it) }
        else -> emptyList()
      }
    return enums(units.fields["units"] ?: VegaValue.Null).distinct().sorted()
  }

  @Test
  fun `every time unit upstream documents is accepted`() {
    val all = upstreamTimeUnits()
    assertTrue(all.size >= 10, "only ${all.size} time units found in the schema: $all")
    val refused = all.filter { unit ->
      compile(
          """
            {"width": 40, "height": 40, "padding": 0,
             "data": [{"name": "t", "values": [{"d": 0}],
                       "transform": [{"type": "timeunit", "field": "d", "units": ["$unit"]}]}],
             "marks": [{"type": "rect", "from": {"data": "t"},
                        "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                             "width": {"value": 5}, "height": {"value": 5}}}}]}
            """
            .trimIndent()
        )
        .diagnostics
        .any { unit in it.message }
    }
    record("timeUnit", all.size, all.size - refused.size, refused)
    assertTrue(refused.isEmpty(), "${refused.size} of ${all.size} time units refused: $refused")
  }

  // ---- expression functions -----------------------------------------------------

  /**
   * The names in upstream's `functionContext`, which is the whole expression vocabulary.
   *
   * Registered **two** ways, and taking only the first is how this test's own first draft came to
   * report 97 of 118. The object literal holds the functions that need nothing from the view; the
   * twenty that do — `scale`, `data`, `geoArea`, the `vlSelection` family — are added under it by
   * `expressionFunction('name', fn, visitor)` calls, because each one also registers a visitor that
   * declares what it depends on. A specification cannot tell the two apart.
   *
   * Within the literal the entries are written three ways — a bare shorthand, a method, and a
   * method taking arguments — so the pattern takes an identifier at two-space indent followed by
   * any of `,`, `(`, `:`, or the end of the line.
   *
   * Names beginning with an underscore are excluded: `__bandwidth` is upstream's own internal
   * helper, reachable from its generated code and not from a specification.
   *
   * Checked against what upstream's own registry answers at runtime, and the two agree exactly in
   * both directions: 118 names, nothing scraped that is not registered and nothing registered that
   * is not scraped.
   */
  private fun upstreamFunctions(): List<String> {
    val source = pinned("vega-functions/src/codegen.js").readText()
    val literal =
      Regex("""^ {2}(\w+)[ \t]*(?:[,(:]|$)""", RegexOption.MULTILINE)
        .findAll(source.substringAfter("export const functionContext = {").substringBefore("\n};"))
        .map { it.groupValues[1] }
    val registered =
      Regex("""expressionFunction\(\s*['"](\w+)['"]""").findAll(source).map { it.groupValues[1] }
    return (literal + registered).filterNot { it.startsWith("_") }.distinct().sorted().toList()
  }

  private val nothing =
    object : ExpressionScope {
      override val datum: VegaValue = VegaValue.Null

      override fun signal(name: String): VegaValue = VegaValue.Null

      override fun dataset(name: String): List<VegaValue> = emptyList()
    }

  /**
   * Whether the **name** resolves, tried at every arity up to four.
   *
   * What is being measured is whether the vocabulary has the word, not whether any one call is well
   * formed — so a name counts as present the moment *some* arity reaches an implementation, and
   * only "unknown function" at every arity counts as absent.
   *
   * The arities are not decoration. The first draft of this called each name once with no arguments
   * and reported sixteen of upstream's functions missing, all of which are implemented: the
   * evaluator dispatches on name **and** argument count — `if (name == "geoShape" && node.arguments
   * .size >= 2)` — so a zero-argument call to any of them falls past its own branch and out of the
   * bottom as unknown. Four is enough for the widest of them, `gradient`, which takes three.
   *
   * Arguments are nulls because their values are irrelevant: a function reached with nonsense
   * arguments throws something, and anything other than "unknown function" says the name resolved.
   */
  private fun known(name: String): Boolean =
    (0..4).any { arity ->
      val call = "$name(${List(arity) { "null" }.joinToString(", ")})"
      val compiled = VegaExpressionCompiler().compile(call)
      if (compiled !is ExpressionResult.Compiled) {
        false
      } else {
        try {
          compiled.expression.evaluate(nothing)
          true
        } catch (failure: ExpressionEvaluationException) {
          !(failure.diagnostic.code == DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION &&
            "Unknown function" in failure.diagnostic.message)
        } catch (other: Exception) {
          true
        }
      }
    }

  @Test
  fun `every expression function upstream registers is implemented`() {
    val all = upstreamFunctions()
    assertTrue(
      all.size >= 110,
      "only ${all.size} functions scraped from upstream's functionContext; the literal's shape " +
        "changed and this measures nothing",
    )
    val missing = all.filterNot { known(it) }
    record("expressionFunction", all.size, all.size - missing.size, missing)
    assertTrue(
      missing.isEmpty(),
      "${missing.size} of ${all.size} of upstream's expression functions are not implemented, and " +
        "no row in SUPPORTED_FEATURES.md would have said so: $missing",
    )
  }

  /**
   * The three probes detect something, checked against names upstream has never had.
   *
   * A guard on the guards. Every coverage probe written here has needed one, and two of the four
   * before it caught a probe measuring its own input rather than the engine.
   */
  @Test
  fun `an invented projection, time unit and function are each detected`() {
    pinned("vega/build/vega-schema.json")
    assertTrue(
      compile(
          """
          {"width": 40, "height": 40, "padding": 0,
           "projections": [{"name": "p", "type": "aProjectionUpstreamHasNeverHad"}],
           "data": [{"name": "t", "values": [{"v": 1}]}],
           "marks": []}
          """
            .trimIndent()
        )
        .diagnostics
        .any { "aProjectionUpstreamHasNeverHad" in it.message },
      "an invented projection drew no diagnostic, so the projection probe detects nothing",
    )
    assertTrue(
      compile(
          """
          {"width": 40, "height": 40, "padding": 0,
           "data": [{"name": "t", "values": [{"d": 0}],
                     "transform": [{"type": "timeunit", "field": "d",
                                    "units": ["aUnitUpstreamHasNeverHad"]}]}],
           "marks": []}
          """
            .trimIndent()
        )
        .diagnostics
        .any { "aUnitUpstreamHasNeverHad" in it.message },
      "an invented time unit drew no diagnostic, so the time-unit probe detects nothing",
    )
    assertTrue(
      !known("aFunctionUpstreamHasNeverHad"),
      "an invented function resolved, so the function probe detects nothing",
    )
  }
}
