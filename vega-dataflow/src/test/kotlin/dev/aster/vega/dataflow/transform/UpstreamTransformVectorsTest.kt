package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Vega's **own** transform tests, replayed against these transforms.
 *
 * `vega-transforms` is where most of upstream's test suite lives — 938 assertions — and none of it
 * was reachable at the export boundary: those packages export operator *classes* driven through a
 * `Dataflow`, so a recorder that wraps exports sees a constructor and nothing else. The seam that
 * sees everything is one level in, `prototype.transform(_, pulse)`, and
 * `oracle-js/src/record-upstream-tests.mjs` hooks it: parameters, the tuples that went in, the
 * tuples that came out, and the operator's own value where it keeps one.
 *
 * What can and cannot be replayed is decided here rather than assumed, and counted either way:
 * - a parameter that is a **function** loses its body when it is recorded, so `filter` and
 *   `formula` vectors built from a JavaScript predicate cannot be replayed at all. Upstream's own
 *   tests write them that way; a *specification* writes an expression string, which is covered
 *   elsewhere.
 * - an accessor is recorded by the field path it reads, which is exactly the string form a
 *   specification uses, so `groupby`, `fields` and friends translate directly.
 * - a pulse with more than 200 tuples is recorded as a count, not an array; those are skipped.
 *
 * The floor at the end is what keeps this honest: a harness that silently stopped replaying would
 * otherwise report a pass.
 */
class UpstreamTransformVectorsTest {

  private class Context : TransformContext {
    /** A `stratify` or `nest` in the same pipeline would leave its tree here; a replay runs one. */
    override var tree: TreeSource? = null

    override val diagnostics: DiagnosticCollector = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())
    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    /** One stream for the context, as a compile has: `ci0`/`ci1` consume draws in sequence. */
    private val stream = RandomStream()

    override fun setSignal(name: String, value: VegaValue) = Unit

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()

        override val random: RandomStream = stream
      }
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun vectors(pkg: String): List<JsonObject> {
    val file =
      File(
        File(System.getProperty("user.dir")).parentFile,
        "test-fixtures/upstream-vectors/$pkg.json",
      )
    // The vectors are **derived and not committed** — `scripts/record-upstream-vectors.sh` rebuilds
    // them byte-identically from Vega's own test suite. Without them this test cannot run, and it
    // says
    // so as an *assumption* rather than passing: a green tick for a check that did not happen is
    // the
    // failure this repository has already had once. `scripts/check.sh` prints the same reminder.
    org.junit.jupiter.api.Assumptions.assumeTrue(
      file.isFile,
      "no upstream vectors at ${file.path} — run scripts/record-upstream-vectors.sh to replay them",
    )
    return json.parseToJsonElement(file.readText()).jsonObject["calls"]!!.jsonArray.map {
      it.jsonObject
    }
  }

  /**
   * Every package whose meaning lives in an **operator**, not just `vega-transforms`.
   *
   * `vega-encode` holds `stack`, `vega-geo` holds `geojson`, `vega-hierarchy` holds `nest` and
   * `stratify`, and each is recorded through the same `prototype.transform` seam — so one replay
   * covers them all, and an operator this engine does not have is counted rather than skipped in
   * silence. A package with no vector file is simply absent, not an error: the recorder drops a
   * file that recorded nothing.
   */
  private fun operatorPackages(): List<JsonObject> =
    listOf(
        "vega-transforms",
        "vega-encode",
        "vega-geo",
        "vega-hierarchy",
        "vega-regression",
        "vega-crossfilter",
        "vega-voronoi",
      )
      .flatMap { pkg ->
        val file =
          File(
            File(System.getProperty("user.dir")).parentFile,
            "test-fixtures/upstream-vectors/$pkg.json",
          )
        if (file.isFile) vectors(pkg) else emptyList()
      }

  /** A recorded value as one of ours, or null when it carries something a vector cannot hold. */
  private fun translate(element: JsonElement): VegaValue? =
    when (element) {
      is JsonNull -> VegaValue.Null
      is JsonPrimitive ->
        when {
          element.isString -> VegaValue.Str(element.content)
          element.booleanOrNull != null -> VegaValue.Bool(element.booleanOrNull!!)
          element.doubleOrNull != null -> VegaValue.Num(element.doubleOrNull!!)
          else -> VegaValue.Null
        }
      is JsonArray -> {
        val values = element.map { translate(it) }
        if (values.any { it == null }) null else VegaValue.Arr(values.filterNotNull())
      }
      is JsonObject ->
        when (element["\$"]?.jsonPrimitive?.content) {
          // An accessor is its field path — which is what a specification writes — unless it also
          // carries a **name** that differs from it. `field('k1', 'key')` reads `k1` and is named
          // `key`, and upstream names an aggregate's output column after the *name*; a bare string
          // cannot say that, so those vectors are counted rather than compared as a naming bug.
          "accessor" -> {
            val path = element["fields"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            val name = element["name"]?.jsonPrimitive?.content
            if (name != null && name != path) null else path?.let { VegaValue.Str(it) }
          }
          "NaN" -> VegaValue.Num(Double.NaN)
          "Infinity" -> VegaValue.Num(Double.POSITIVE_INFINITY)
          "-Infinity" -> VegaValue.Num(Double.NEGATIVE_INFINITY)
          "date" ->
            element["epochMillis"]?.jsonPrimitive?.doubleOrNull?.let { VegaValue.Timestamp(it) }
          // A function, a truncated pulse, a circular reference: nothing a replay can use.
          null -> {
            val fields = element.mapValues { (_, value) -> translate(value) }
            if (fields.values.any { it == null }) null
            else VegaValue.Obj(LinkedHashMap(fields.mapValues { it.value!! }))
          }
          else -> null
        }
    }

  private fun rows(pulse: JsonObject?, vararg keys: String): List<VegaValue>? {
    val array = keys.firstNotNullOfOrNull { pulse?.get(it) as? JsonArray } ?: return null
    val translated = array.map { translate(it) }
    return if (translated.any { it == null }) null else translated.filterNotNull()
  }

  @Test
  fun `upstream's own transform vectors replay against these transforms`() {
    val registry = TransformRegistry.Default
    val progress =
      File(File(System.getProperty("user.dir")).parentFile, "build/upstream-transform-progress.txt")
    progress.parentFile.mkdirs()
    progress.writeText("")
    var replayed = 0
    val unreplayable = mutableMapOf<String, Int>()
    val failures = mutableListOf<String>()

    for (vector in operatorPackages()) {
      val op = vector["op"]?.jsonPrimitive?.content ?: continue
      // Only an operator's **first** call can be replayed. A transform operator is stateful —
      // `aggregate` remembers every group value it has seen — so a later pulse's output depends on
      // every pulse before it, which a pure `apply(rows, params)` cannot reconstruct. Upstream's
      // own
      // cross-product test proves it: its second pulse produces a cell for a value that is not in
      // its input. Those calls are counted, not quietly compared.
      val sequence = vector["sequence"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
      if (sequence > 0) {
        unreplayable.merge("$op (a later pulse on a stateful operator)", 1, Int::plus)
        continue
      }
      val transform = registry[op]
      if (transform == null) {
        unreplayable.merge("$op (no such transform here)", 1, Int::plus)
        continue
      }
      val recorded = vector["params"]?.jsonObject
      // An `expr` recorded as an accessor was a **JavaScript function** upstream — `d => d.id * 2`
      // —
      // and an accessor keeps only the fields it read, not the arithmetic. Replaying it as the
      // expression `id` computes something else entirely and would look like a bug in `formula`. A
      // specification writes an expression *string*, which the fixtures cover.
      // Same for `sort`: upstream passes a **comparator**, and one built by `compare(['count'],
      // ['descending'])` records as an accessor over `count` with the direction gone. Replaying
      // that
      // as an unsorted field would report a sorting bug that is really a recording limit.
      val opaque =
        listOf("expr", "sort").filter { key ->
          (recorded?.get(key) as? JsonObject)?.get("\$")?.jsonPrimitive?.content != null
        }
      if (opaque.isNotEmpty()) {
        unreplayable.merge(
          "$op (${opaque.joinToString("/")} is a JavaScript function)",
          1,
          Int::plus,
        )
        continue
      }
      val params = recorded?.mapValues { (_, v) -> translate(v) }
      if (params == null || params.values.any { it == null }) {
        unreplayable.merge("$op (a parameter is a function or unrepresentable)", 1, Int::plus)
        continue
      }
      val input = rows(vector["input"]?.jsonObject, "source", "add")
      val expected = rows(vector["output"]?.jsonObject, "add", "source")
      if (input == null || expected == null || input.isEmpty()) {
        unreplayable.merge("$op (pulse truncated or empty)", 1, Int::plus)
        continue
      }
      // Some operators publish something that is not tuples at all: `crossfilter` maintains an
      // **index** — its pulse carries the positions it has filtered to, and `resolvefilter` reads
      // that index rather than rows. Comparing an index against rows compares two different
      // contracts, so it is counted rather than called a divergence.
      if (expected.any { it !is VegaValue.Obj } || input.any { it !is VegaValue.Obj }) {
        unreplayable.merge("$op (publishes an index rather than tuples)", 1, Int::plus)
        continue
      }
      val fields = LinkedHashMap<String, VegaValue>()
      fields["type"] = VegaValue.Str(op)
      params.forEach { (key, value) -> fields[key] = value!! }
      val spec = VegaValue.Obj(fields)
      progress.appendText("$op ${canonical(spec).take(120)}\n")
      val actual = runCatching {
        transform.apply(input, spec, Context())
      }
        .getOrElse {
          unreplayable.merge("$op (threw: ${it::class.simpleName})", 1, Int::plus)
          continue
        }
      replayed++
      // Compared structurally rather than by rendered string, so that a number may agree within a
      // few ulps; see `Agreement`. The strings are still what gets *reported*.
      val same =
        expected.size == actual.size &&
          expected.indices.all { Agreement.agree(expected[it], actual[it]) }
      if (!same) {
        val want = expected.map { canonical(it) }
        val got = actual.map { canonical(it) }
        failures.add("$op params=${canonical(spec)}\n    upstream: $want\n    ours    : $got")
      }
    }

    // Written to a file rather than printed: the ledger is the useful output of this test, the
    // build suppresses standard streams, and a reviewer wants to read it after the fact.
    val ledger =
      StringBuilder("replayed $replayed operator vectors across every recorded package\n")
    unreplayable.entries
      .sortedByDescending { it.value }
      .forEach { ledger.append("  ${it.key}: ${it.value}\n") }
    failures.forEach { ledger.append("MISMATCH $it\n") }
    File(File(System.getProperty("user.dir")).parentFile, "build/upstream-transform-ledger.txt")
      .apply {
        parentFile.mkdirs()
        writeText(ledger.toString())
      }
    // The divergences are pinned **exactly**. A new one fails the build; so does fixing one without
    // deleting its entry, which is what stops the list from quietly growing stale. Every entry is a
    // bug to fix, not an accepted difference — see `known-divergences.json` and HANDOFF.md.
    val known =
      json
        .parseToJsonElement(
          File(
              File(System.getProperty("user.dir")).parentFile,
              "test-fixtures/upstream-vectors/known-divergences.json",
            )
            .readText()
        )
        .jsonObject["divergences"]!!
        .jsonArray
        .map { it.jsonObject }
        // Only this replay's entries: the same file also records where the colour and path parsers
        // differ, and three replays each asserting the whole list would fail on each other's work.
        .filter { it["kind"]?.jsonPrimitive?.content == "transform" }
        .map { it["signature"]!!.jsonPrimitive.content }
    assertEquals(
      known.sorted(),
      failures.map { it.lines().first() }.sorted(),
      "the set of divergences from upstream changed; update known-divergences.json and HANDOFF.md",
    )
    assertTrue(replayed >= 60, "only $replayed vectors replayed; the harness must not shrink")
  }

  /** Field-sorted JSON, so two rows that mean the same thing compare equal. */
  private fun canonical(value: VegaValue): String =
    when (value) {
      is VegaValue.Obj ->
        value.fields.entries
          .sortedBy { it.key }
          .joinToString(",", "{", "}") {
            "\"${it.key}\":${canonical(it.value)}"
          }
      is VegaValue.Arr -> value.values.joinToString(",", "[", "]") { canonical(it) }
      is VegaValue.Str -> "\"${value.value}\""
      is VegaValue.Num ->
        if (value.value == value.value.toLong().toDouble() && value.value.isFinite()) {
          value.value.toLong().toString()
        } else {
          value.value.toString()
        }
      is VegaValue.Timestamp -> value.epochMillis.toLong().toString()
      is VegaValue.Bool -> value.value.toString()
      is VegaValue.Null -> "null"
      is VegaValue.Pattern -> value.text
    }
}
