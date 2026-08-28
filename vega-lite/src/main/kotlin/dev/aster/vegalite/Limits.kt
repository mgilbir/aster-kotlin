package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * What a Vega-Lite document may not exceed, and why there is a number at all.
 *
 * A specification is **pasted text**: `VegaLiteInput.toVega` takes a string, and the demo apps on
 * three platforms have a paste screen. So the only interesting question about a pathological
 * document is what it does to the host, and until this file the answer was `StackOverflowError` —
 * an `Error`, caught by nothing typed here, and on Kotlin/Native not catchable at all.
 *
 * Upstream refuses the same documents, with a `RangeError` from V8's own stack guard, so refusing
 * is faithful and only the *mechanism* is being improved: a diagnostic that names the limit, from a
 * compiler that returns rather than one that dies. Measured against vega-lite 6.4.3: 10,000
 * transforms, 5,000 nested layers and 5,000 nested `vconcat`s each throw there.
 *
 * The two limits are the two ways a document buys recursion:
 *
 * - **Nesting**, which recurses through the view builders — `collect` over a layer's members, and
 *   the concat walk beside it. This engine overflowed somewhere past 2,000; upstream past 200.
 * - **Transforms**, which is not nesting at all. Each becomes one node in a `DataNode` chain, and
 *   the eight optimizer passes over that chain are each written as "do this node, then the
 *   children" — so a *flat* list of 2,000 transforms is a 2,000-deep recursion. That is the
 *   surprising one, and it is why a limit on nesting alone would not have been enough.
 *
 * Both numbers are two orders of magnitude above anything in the 283-fixture corpus, and a
 * comfortable factor below where this engine actually falls over, because the real margin depends
 * on the thread's stack size and that is the host's to choose.
 */
internal object Limits {

  /** How deeply views may nest before the document is refused. */
  const val MAX_VIEW_DEPTH: Int = 64

  /** How many `transform` entries a whole document may declare. */
  const val MAX_TRANSFORMS: Int = 512

  /** The keys under which one view holds others; each is a level of nesting. */
  private val COMPOSITIONS =
    listOf("layer", "concat", "hconcat", "vconcat", "facet", "repeat", "spec")

  /**
   * Whether [spec] is within both limits, reporting the first one it is not.
   *
   * Walks with an explicit stack rather than by recursion, because a function checking whether a
   * document is too deep to recurse over must not be the thing that overflows on it.
   */
  fun check(spec: VegaValue, diagnostics: DiagnosticCollector): Boolean {
    var transforms = 0
    // Depth here counts *view* nesting, and a view's own contents can be arbitrarily deep JSON
    // without recursing the compiler — a `values` array of ten thousand rows is one level. So the
    // walk carries the depth of the nearest enclosing composition rather than the JSON depth.
    val pending = ArrayDeque<Pair<VegaValue, Int>>()
    pending.addLast(spec to 0)
    while (pending.isNotEmpty()) {
      val (value, depth) = pending.removeLast()
      when (value) {
        is VegaValue.Obj -> {
          for ((key, child) in value.fields) {
            if (key == "transform" && child is VegaValue.Arr) {
              transforms += child.values.size
              if (transforms > MAX_TRANSFORMS) {
                diagnostics.fatal(
                  VegaLiteDiagnostics.LIMIT_EXCEEDED,
                  "This specification declares more than $MAX_TRANSFORMS transforms. Each one " +
                    "becomes a node in a data-flow chain that the optimizer walks recursively, so " +
                    "past this many the compiler runs out of stack rather than answering. " +
                    "Upstream refuses the same document, with a `RangeError`.",
                  jsonPath = "$.transform",
                )
                return false
              }
            }
            val next = if (key in COMPOSITIONS) depth + 1 else depth
            if (next > MAX_VIEW_DEPTH) {
              diagnostics.fatal(
                VegaLiteDiagnostics.LIMIT_EXCEEDED,
                "This specification nests views more than $MAX_VIEW_DEPTH deep. The compiler " +
                  "walks a composition recursively, so past this depth it runs out of stack " +
                  "rather than answering; upstream refuses the same document, with a `RangeError`. " +
                  "The deepest chart anyone draws is a handful of levels.",
                jsonPath = "$.$key",
              )
              return false
            }
            // Only a composition's contents can hold another view; everything else is data or
            // properties, and walking it is just as necessary for the transform count but never
            // adds depth.
            pending.addLast(child to next)
          }
        }
        is VegaValue.Arr -> for (element in value.values) pending.addLast(element to depth)
        else -> Unit
      }
    }
    return true
  }
}
