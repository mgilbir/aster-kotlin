package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The transforms against upstream, for the divergences an audit found by reading.
 *
 * Every expectation here was read off the pinned Vega running the same transform over the same
 * rows, through a small script in `oracle-js`:
 * ```
 * node --input-type=module -e "import * as vega from 'vega';
 *   const view = new vega.View(vega.parse({data: [{name: 't', values: [{v: 3}, {v: -5}],
 *     transform: [{type: 'stack', field: 'v', offset: 'center'}]}]}), {renderer: 'none'});
 *   await view.runAsync(); console.log(JSON.stringify(view.data('t')))"
 * ```
 *
 * Several of these are answers a careful reader would not have guessed, and two of them — `stack`'s
 * grouping and `aggregate`'s — turned out to disagree with **each other** upstream.
 */
class DataflowFidelityTest {

  private fun row(vararg fields: Pair<String, VegaValue>) = VegaValue.Obj(linkedMapOf(*fields))

  private fun num(value: Double) = VegaValue.Num(value)

  /** The smallest context a transform needs: diagnostics, a compiler and an empty scope. */
  private class Context(override val diagnostics: DiagnosticCollector = DiagnosticCollector()) :
    TransformContext {
    override var tree: TreeSource? = null

    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())

    private val signals = LinkedHashMap<String, VegaValue>()

    private val stream = RandomStream()

    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      signals[name] = value
    }

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = signals[name] ?: VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()

        override val random: RandomStream = stream
      }
  }

  private fun apply(
    transform: Transform,
    input: List<VegaValue>,
    vararg params: Pair<String, VegaValue>,
  ): List<VegaValue> = transform.apply(input, VegaValue.Obj(linkedMapOf(*params)), Context())

  private fun arr(vararg values: String) = VegaValue.Arr(values.map { VegaValue.Str(it) })

  // ---- C3: window keys by position ------------------------------------------

  /**
   * Two rows that are structurally identical are two rows.
   *
   * `VegaValue.Obj` is a value class over a map and compares structurally, so keying the results by
   * the row itself collapsed duplicates onto the last one's answer: `[{v:1},{v:1}]` with
   * `ops:["sum"]` came back as `[2, 2]` where upstream answers `[1, 2]`. Duplicate rows are
   * ordinary, and none of the fourteen replayed window vectors has one.
   */
  @Test
  fun `window annotates duplicate rows separately`() {
    val input = listOf(row("v" to num(1.0)), row("v" to num(1.0)), row("v" to num(1.0)))
    val out =
      apply(
        WindowTransform,
        input,
        "ops" to arr("sum"),
        "fields" to arr("v"),
        "as" to arr("s"),
      )
    assertEquals(listOf(1.0, 2.0, 3.0), out.map { it.field("s").asDouble() })

    val numbered = apply(WindowTransform, input, "ops" to arr("row_number"), "as" to arr("n"))
    assertEquals(listOf(1.0, 2.0, 3.0), numbered.map { it.field("n").asDouble() })
  }

  // ---- M44: what makes two rows one group -----------------------------------

  /**
   * `aggregate` and `window` group through upstream's object-backed `fastmap`, so the number `1001`
   * and the string `"1001"` are **one** group. `stack` partitions with
   * `JSON.stringify(groupby.map(get))` instead, so there they are **two**.
   *
   * Probed both ways round, because it is exactly the sort of thing one keying function would have
   * been assumed to cover.
   */
  @Test
  fun `aggregate merges a number with its own text and stack does not`() {
    val input =
      listOf(
        row("k" to num(1001.0), "v" to num(1.0)),
        row("k" to VegaValue.Str("1001"), "v" to num(2.0)),
        row("k" to num(1001.0), "v" to num(4.0)),
      )
    val aggregated =
      apply(
        AggregateTransform,
        input,
        "groupby" to arr("k"),
        "ops" to arr("sum"),
        "fields" to arr("v"),
        "as" to arr("s"),
      )
    assertEquals(1, aggregated.size)
    assertEquals(7.0, aggregated[0].field("s").asDouble())

    val stacked = apply(StackTransform, input, "field" to VegaValue.Str("v"), "groupby" to arr("k"))
    assertEquals(listOf(1.0, 2.0, 5.0), stacked.map { it.field("y1").asDouble() })
  }

  // ---- H20 / L51: stack offsets ---------------------------------------------

  /**
   * `stackCenter` uses **one** cursor over the absolute values, starting at `(max - sum) / 2`.
   *
   * Splitting it into positive and negative cursors, as `zero` correctly does, made a group holding
   * both signs grow in two directions from the centre line: `[3, -5]` spans `[0,3]` and `[3,8]`
   * upstream and spanned `[0,3]` and `[0,-5]` here. The one committed `center` fixture is
   * all-positive, where the two rules agree.
   */
  @Test
  fun `stack center runs one cursor over the absolute values`() {
    val input = listOf(row("v" to num(3.0)), row("v" to num(-5.0)))
    val centred =
      apply(
        StackTransform,
        input,
        "field" to VegaValue.Str("v"),
        "offset" to VegaValue.Str("center"),
      )
    assertEquals(listOf(0.0, 3.0), centred.map { it.field("y0").asDouble() })
    assertEquals(listOf(3.0, 8.0), centred.map { it.field("y1").asDouble() })

    // `zero` keeps its two cursors, which is what upstream does there.
    val zeroed = apply(StackTransform, input, "field" to VegaValue.Str("v"))
    assertEquals(listOf(0.0, 0.0), zeroed.map { it.field("y0").asDouble() })
    assertEquals(listOf(3.0, -5.0), zeroed.map { it.field("y1").asDouble() })
  }

  /**
   * `stackNormalize` is `scale = 1 / group.sum`, written as a reciprocal.
   *
   * Which is what decides a group summing to nothing: `1 / 0` is Infinity, `Infinity * 0` is NaN,
   * and a mark at NaN is not drawn. Guarding the zero and answering 0 drew the whole group flat
   * along the baseline — a band of zero-height rectangles upstream leaves out.
   */
  @Test
  fun `stack normalize over a zero sum is not drawn`() {
    val out =
      apply(
        StackTransform,
        listOf(row("v" to num(0.0)), row("v" to num(0.0))),
        "field" to VegaValue.Str("v"),
        "offset" to VegaValue.Str("normalize"),
      )
    assertEquals(0.0, out[0].field("y0").asDouble())
    assertTrue(out[0].field("y1").asDouble().isNaN(), "the first bar's top")
    assertTrue(out[1].field("y0").asDouble().isNaN(), "the second bar's base")
  }

  // ---- H21 / M42 / M43 / L47 / L48 / L49: the aggregate cell -----------------

  private fun summarise(input: List<VegaValue>, op: String): VegaValue {
    val out =
      apply(
        AggregateTransform,
        input,
        "ops" to arr(op),
        "fields" to arr("v"),
        "as" to arr("r"),
      )
    return out.single().field("r")
  }

  /**
   * Upstream's cell sorts every value into one of three boxes, and the boundaries are not obvious:
   * ```js
   * if (v == null || v === '') { ++this.missing; return; }
   * if (v !== v) return;   // a NaN is neither missing nor valid
   * ++this.valid;
   * ```
   */
  @Test
  fun `the empty string is missing and a NaN is in neither box`() {
    val dirty = listOf(row("v" to num(1.0)), row("v" to VegaValue.Str("")))
    assertEquals(1.0, summarise(dirty, "sum").asDouble())
    assertEquals(1.0, summarise(dirty, "mean").asDouble())
    assertEquals(1.0, summarise(dirty, "valid").asDouble())
    assertEquals(1.0, summarise(dirty, "missing").asDouble())

    // A NaN is counted by neither `valid` nor `missing`.
    val withNaN = listOf(row("v" to num(1.0)), row("v" to num(Double.NaN)))
    assertEquals(1.0, summarise(withNaN, "valid").asDouble())
    assertEquals(0.0, summarise(withNaN, "missing").asDouble())
  }

  /**
   * A value that merely *coerces* to NaN is **valid**, and poisons everything computed from it.
   *
   * Filtering it out answered 1 for the sum of `[1, "abc"]` where upstream answers NaN — a total
   * that silently omits the rows it could not read, which is the failure this project exists to
   * refuse. An infinity is valid too, and takes the maximum.
   */
  @Test
  fun `a value that cannot be read poisons the sum rather than being skipped`() {
    val mixed = listOf(row("v" to num(1.0)), row("v" to VegaValue.Str("abc")))
    assertTrue(summarise(mixed, "sum").asDouble().isNaN())
    assertEquals(2.0, summarise(mixed, "valid").asDouble())
    // `min` and `max` track the extreme incrementally over the **raw** values with JavaScript's
    // `<`, so a string never displaces a number: `1 < "abc"` and `"abc" < 1` are both false.
    assertEquals(1.0, summarise(mixed, "min").asDouble())
    assertEquals(1.0, summarise(mixed, "max").asDouble())

    val infinite = listOf(row("v" to num(Double.POSITIVE_INFINITY)), row("v" to num(1.0)))
    assertEquals(1.0, summarise(infinite, "min").asDouble())
    assertEquals(Double.POSITIVE_INFINITY, summarise(infinite, "max").asDouble())
  }

  /** `m.valid ? … : undefined` guards every numeric operation, `sum` included. */
  @Test
  fun `a group with no valid value has no sum at all`() {
    val nothing = listOf(row("v" to VegaValue.Null), row("v" to VegaValue.Null))
    val out =
      apply(
        AggregateTransform,
        nothing,
        "ops" to arr("sum"),
        "fields" to arr("v"),
        "as" to arr("r"),
      )
    // Absent from the row, not null and not zero: a zero passes an `isValid` filter that upstream's
    // answer does not.
    assertTrue("r" !in (out.single() as VegaValue.Obj).fields, out.single().toString())
  }

  /** `variance`, `stdev` and `stderr` need two values; `variancep` and `stdevp` need one. */
  @Test
  fun `the sample variance of one value is absent, not NaN`() {
    val one = listOf(row("v" to num(4.0)))
    val out =
      apply(
        AggregateTransform,
        one,
        "ops" to
          VegaValue.Arr(
            listOf("variance", "stdev", "stderr", "variancep").map { VegaValue.Str(it) }
          ),
        "fields" to arr("v", "v", "v", "v"),
        "as" to arr("a", "b", "c", "d"),
      )
    val fields = (out.single() as VegaValue.Obj).fields
    assertEquals(setOf("d"), fields.keys.intersect(setOf("a", "b", "c", "d")))
    assertEquals(0.0, fields.getValue("d").asDouble())
  }

  /**
   * `argmin` reaches its answer by a different route from `min`, and they genuinely disagree.
   *
   * `m.argmin || m.cell.data.argmin(m.get)` falls back to `extentIndex` over every stored row, so
   * `argmin` over `[Infinity, 1]` is the second row while `min` is 1, and `argmin` over `['abc',
   * 1]` is the **first** row while `min` is `'abc'`. An infinity and a non-numeric value both take
   * part, where this used to skip them.
   */
  @Test
  fun `argmin takes part where an infinity and a string are involved`() {
    val infinite =
      listOf(
        row("v" to num(Double.POSITIVE_INFINITY), "i" to num(0.0)),
        row("v" to num(1.0), "i" to num(1.0)),
      )
    val out =
      apply(
        AggregateTransform,
        infinite,
        "ops" to arr("argmin", "argmax"),
        "fields" to arr("v", "v"),
        "as" to arr("lo", "hi"),
      )
    assertEquals(1.0, out.single().field("lo").field("i").asDouble())
    assertEquals(0.0, out.single().field("hi").field("i").asDouble())
  }

  // ---- H23 / L52: pie -------------------------------------------------------

  /**
   * `sort` decides which row gets which sweep and leaves the rows where they were.
   *
   * It is a boolean in upstream's own `Definition` and nothing here read it, so a chart asking for
   * its biggest slice first got its slices in data order and no diagnostic.
   */
  @Test
  fun `pie sorts the sweeps and not the rows`() {
    val input = listOf(row("v" to num(3.0)), row("v" to num(1.0)), row("v" to num(2.0)))
    val out =
      apply(
        PieTransform,
        input,
        "field" to VegaValue.Str("v"),
        "sort" to VegaValue.Bool(true),
      )
    // Row order is unchanged; the *angles* are assigned smallest-first.
    assertEquals(listOf(3.0, 1.0, 2.0), out.map { it.field("v").asDouble() })
    val starts = out.map { it.field("startAngle").asDouble() }
    assertTrue(starts[1] < starts[2] && starts[2] < starts[0], starts.toString())
  }

  /** A negative slice runs backwards over its neighbour upstream; taking `abs` hid that. */
  @Test
  fun `pie does not correct a negative value`() {
    val out =
      apply(
        PieTransform,
        listOf(row("v" to num(3.0)), row("v" to num(-1.0))),
        "field" to VegaValue.Str("v"),
      )
    val ends = out.map { it.field("endAngle").asDouble() }
    assertTrue(ends[0] > 9.0 && ends[0] < 9.5, "the first slice overshoots: $ends")
  }

  // ---- H24: bin steps -------------------------------------------------------

  /**
   * `steps` limits the choice to a listed set: `v = span / maxbins`, then the largest listed step
   * still below `v`. Neither read nor reported before, so a chart asking to be binned only on the
   * round numbers it has axis labels for got whatever the automatic rule chose.
   */
  @Test
  fun `bin honours an allowed set of steps`() {
    val settings =
      BinTransform.binSettings(
        min = 0.0,
        max = 100.0,
        maxbins = 10,
        base = 10.0,
        step = null,
        steps = listOf(1.0, 3.0, 25.0),
        divide = listOf(5.0, 2.0),
        minstep = 0.0,
        nice = true,
      )
    assertEquals(3.0, settings.step)
  }

  // ---- L55: the two window operations whose parameter is required -----------

  /** `if (!(num > 0)) error(...)`: upstream refuses the specification rather than choosing one. */
  @Test
  fun `ntile without a parameter is refused rather than defaulted`() {
    val diagnostics = DiagnosticCollector()
    val out =
      WindowTransform.apply(
        listOf(row("v" to num(1.0))),
        VegaValue.Obj(linkedMapOf("ops" to arr("ntile"), "as" to arr("n"))),
        Context(diagnostics),
      )
    assertEquals(1, out.size)
    assertTrue("n" !in (out.single() as VegaValue.Obj).fields)
    assertTrue(
      diagnostics.diagnostics.any { "greater than zero" in it.message },
      diagnostics.diagnostics.toString(),
    )
  }
}
