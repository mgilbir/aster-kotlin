package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Vega-Lite **4**'s `selection` spelling, rewritten into the `params` one before anything reads it.
 *
 * Vega-Lite 5 replaced `selection` with `params`, and upstream kept the old spelling working: its
 * `SelectionCompatibilityNormalizer` runs *before* the core normalizer and leaves nothing for the
 * rest of the compiler to know about. This is that pass, and it is deliberately a port rather than
 * an interpretation — the shapes it rewrites and the order it does them in are upstream's, because
 * agreeing with upstream is the whole point.
 *
 * **Why it is worth having.** A sweep of 1981 charts collected from public repositories found 237
 * that use the v4 spelling, and they matched upstream's compiler on **none** of them — not because
 * the output was slightly off, but because a selection this compiler never saw produces no store
 * dataset, no signals, no `interactive` on the marks it applies to and no cursor. Every one of the
 * top-ranked differences in that sweep was a symptom of this single gap.
 *
 * ### The six shapes, and one that is easy to get wrong
 *
 * A `selection` can appear as a definition and in five kinds of reference:
 *
 * 1. `selection: {name: {…}}` on a unit → `params: [{name, value, select, bind}]`
 * 2. `transform: [{filter: {selection: …}}]` → a predicate
 * 3. `transform: [{bin: {extent: {selection: …}}}]` → `extent: {param: …}`
 * 4. `transform: [{lookup: …, from: {selection: …}}]` → `from: {param: …}`
 * 5. `encoding.«ch».scale.domain: {selection: …}` → `{param: …}`
 * 6. `encoding.«ch».condition` → the condition's `test`, as a predicate
 *
 * The sixth is the one a translation gets wrong. A v4 condition does **not** become `{param:
 * "name"}`; it becomes a `test` holding a predicate, and a condition that already carries `param`
 * is left alone. Read off upstream's own source after an experiment against its compiler had
 * suggested — wrongly — that a straight `selection`→`param` rename would do.
 *
 * ### Emptiness, which travels in both directions
 *
 * `empty` lives on the v4 *definition* and on the v5 *predicate*, so it has to move. Upstream reads
 * `empty !== 'none'`, records it per selection name, and back-patches predicates it has already
 * emitted — the definition may appear after a condition that tests it.
 *
 * The same answer is reached here in **two passes** rather than by mutation: every definition in
 * the tree is collected first, then the references are rewritten against the finished map. That is
 * equivalent because the value upstream converges on is the definition's, wherever it appeared, and
 * a pass that already knows every definition needs no back-patching. It is also the only version
 * that can be written against an immutable tree.
 *
 * A reference to a name nothing defines gets `empty = true`, which is upstream's `?? true`.
 */
internal object SelectionCompat {

  /** Whether anything in this tree uses the v4 spelling, so an untouched spec is not rebuilt. */
  fun applies(spec: VegaValue.Obj): Boolean = mentionsSelection(spec)

  fun normalize(spec: VegaValue.Obj): VegaValue.Obj {
    val empties = HashMap<String, Boolean>()
    collectEmpties(spec, empties)
    return rewrite(spec, empties) as VegaValue.Obj
  }

  // MARK: pass one — what every definition says about emptiness

  /**
   * `emptySelections[name] = empty !== 'none'`, for every definition anywhere in the tree.
   *
   * Last definition wins, which is upstream's behaviour when two units declare the same name: it
   * assigns into one map as it walks and then back-patches every predicate from it.
   */
  private fun collectEmpties(node: VegaValue, into: MutableMap<String, Boolean>) {
    when (node) {
      is VegaValue.Obj -> {
        (node.fields["selection"] as? VegaValue.Obj)?.fields?.forEach { (name, definition) ->
          val empty = (definition as? VegaValue.Obj)?.fields?.get("empty")
          into[name] = (empty as? VegaValue.Str)?.value != "none"
        }
        node.fields.values.forEach { collectEmpties(it, into) }
      }
      is VegaValue.Arr -> node.values.forEach { collectEmpties(it, into) }
      else -> Unit
    }
  }

  // MARK: pass two — the rewrite

  private fun rewrite(node: VegaValue, empties: Map<String, Boolean>): VegaValue =
    when (node) {
      is VegaValue.Arr -> arr(node.values.map { rewrite(it, empties) })
      is VegaValue.Obj -> rewriteObject(node, empties)
      else -> node
    }

  private fun rewriteObject(node: VegaValue.Obj, empties: Map<String, Boolean>): VegaValue.Obj {
    var fields = LinkedHashMap(node.fields)

    // 1. The definition. Emitted where the `selection` stood, so a unit inside a layer keeps its
    //    own parameters rather than having them lifted to the chart, which is where upstream puts
    //    them too — `mapUnit`, on the unit.
    val definition = fields["selection"] as? VegaValue.Obj
    if (definition != null) {
      fields.remove("selection")
      val declared = definition.fields.map { (name, body) -> parameter(name, body) }
      // Appended to any `params` already present rather than replacing them: a specification may
      // mix the two spellings, and upstream's spread leaves an existing `params` in place.
      val existing = (fields["params"] as? VegaValue.Arr)?.values.orEmpty()
      fields["params"] = arr(existing + declared)
    }

    // 2, 3, 4. The transform forms.
    (fields["transform"] as? VegaValue.Arr)?.let { transforms ->
      fields["transform"] = arr(transforms.values.map { rewriteTransform(it, empties) })
    }

    // 5, 6. The encoding forms.
    (fields["encoding"] as? VegaValue.Obj)?.let { encoding ->
      fields["encoding"] =
        VegaValue.Obj(
          LinkedHashMap(encoding.fields).apply {
            encoding.fields.forEach { (channel, def) -> this[channel] = channelDef(def, empties) }
          }
        )
    }

    // Everything else, including the composition members that hold the units.
    fields =
      LinkedHashMap<String, VegaValue>().apply {
        fields.forEach { (key, value) ->
          this[key] =
            when (key) {
              // Already handled above; rewriting them again would walk a `params` entry as if its
              // `select` were an encoding.
              "params",
              "transform",
              "encoding" -> value
              else -> rewrite(value, empties)
            }
        }
      }

    return VegaValue.Obj(fields)
  }

  /**
   * One v4 selection definition as a v5 parameter.
   *
   * `{init: value, bind, empty, ...select}` — upstream's destructure, and the three names it lifts
   * out are the point: `init` becomes the parameter's `value`, `bind` stays on the parameter, and
   * **`empty` goes nowhere near `select`**. It is a property of the predicates that test this
   * selection, which pass two writes for them.
   */
  private fun parameter(name: String, body: VegaValue): VegaValue.Obj {
    val definition = body as? VegaValue.Obj ?: VegaValue.EmptyObject
    val select = LinkedHashMap<String, VegaValue>()
    definition.fields.forEach { (key, value) ->
      if (key != "init" && key != "bind" && key != "empty") select[key] = value
    }
    // `single` and `multi` are both `point`; the difference between them is whether a second click
    // adds to the selection, which is `toggle`.
    when ((select["type"] as? VegaValue.Str)?.value) {
      "single" -> {
        select["type"] = str("point")
        select["toggle"] = bool(false)
      }
      "multi" -> select["type"] = str("point")
    }
    return obj {
      put("name", name)
      put("value", definition.fields["init"])
      put("select", VegaValue.Obj(select))
      put("bind", definition.fields["bind"])
    }
  }

  private fun rewriteTransform(node: VegaValue, empties: Map<String, Boolean>): VegaValue {
    val transform = node as? VegaValue.Obj ?: return node
    // A `filter` becomes a predicate. Upstream tests `isFilter` first, so a transform carrying both
    // is read as a filter.
    if (transform.has("filter")) {
      return VegaValue.Obj(
        LinkedHashMap(transform.fields).apply {
          this["filter"] = predicate(transform.fields["filter"], empties)
        }
      )
    }
    // A `bin` whose extent is a selection.
    (transform.fields["bin"] as? VegaValue.Obj)?.let { bin ->
      val extent = bin.fields["extent"] as? VegaValue.Obj
      if (extent?.has("selection") == true) {
        return VegaValue.Obj(
          LinkedHashMap(transform.fields).apply {
            this["bin"] =
              VegaValue.Obj(
                LinkedHashMap(bin.fields).apply { this["extent"] = renameToParam(extent) }
              )
          }
        )
      }
    }
    // A `lookup` selecting its source.
    (transform.fields["from"] as? VegaValue.Obj)?.let { from ->
      if (transform.has("lookup") && from.has("selection")) {
        return VegaValue.Obj(
          LinkedHashMap(transform.fields).apply { this["from"] = renameToParam(from) }
        )
      }
    }
    return transform
  }

  /**
   * A channel definition: a `scale.domain` selection, a bin extent, and the conditions.
   *
   * Recursive, because upstream calls `normalizeChannelDef` on a condition before reading it — a
   * condition is itself a channel definition and may carry its own scale or bin.
   */
  private fun channelDef(node: VegaValue, empties: Map<String, Boolean>): VegaValue {
    val def = node as? VegaValue.Obj ?: return node
    var fields = LinkedHashMap(def.fields)

    (fields["bin"] as? VegaValue.Obj)?.let { bin ->
      val extent = bin.fields["extent"] as? VegaValue.Obj
      if (extent?.has("selection") == true) {
        fields["bin"] =
          VegaValue.Obj(LinkedHashMap(bin.fields).apply { this["extent"] = renameToParam(extent) })
      }
    }

    (fields["scale"] as? VegaValue.Obj)?.let { scale ->
      val domain = scale.fields["domain"] as? VegaValue.Obj
      if (domain?.has("selection") == true) {
        fields["scale"] =
          VegaValue.Obj(
            LinkedHashMap(scale.fields).apply { this["domain"] = renameToParam(domain) }
          )
      }
    }

    when (val condition = fields["condition"]) {
      is VegaValue.Arr ->
        fields["condition"] = arr(condition.values.map { conditionEntry(it, empties) })
      is VegaValue.Obj -> fields["condition"] = conditionEntry(condition, empties)
      else -> Unit
    }

    return VegaValue.Obj(fields)
  }

  /**
   * One conditional entry.
   *
   * **A `test`, not a `param`.** This is the shape a straight rename gets wrong: upstream drops the
   * entry's `selection`, `param` and `test` keys and writes the predicate into `test`. An entry
   * that already carries `param` is returned untouched, so a v5 condition passing through this pass
   * is not disturbed.
   */
  private fun conditionEntry(node: VegaValue, empties: Map<String, Boolean>): VegaValue {
    val entry = channelDef(node, empties) as? VegaValue.Obj ?: return node
    if (entry.has("param")) return entry
    if (!entry.has("selection")) return entry
    val rest = LinkedHashMap(entry.fields)
    rest.remove("selection")
    rest.remove("param")
    rest.remove("test")
    rest["test"] = predicate(entry, empties)
    return VegaValue.Obj(rest)
  }

  /**
   * `{selection: …}` or a `test`/`filter` holding one, as a v5 predicate.
   *
   * The selection side may be a **composition** — `{selection: {and: ["a", "b"]}}` — so the logical
   * operators are walked and each leaf name becomes `{param, empty}`. Anything that is not a
   * selection reference is left exactly as it was, which is what keeps an ordinary expression
   * filter untouched.
   */
  private fun predicate(node: VegaValue?, empties: Map<String, Boolean>): VegaValue {
    val operand = node as? VegaValue.Obj
    operand?.fields?.get("selection")?.let {
      return composition(it, empties)
    }
    val inner = operand?.fields?.get("test") ?: operand?.fields?.get("filter") ?: node
    return logical(inner) { leaf ->
      (leaf as? VegaValue.Obj)?.fields?.get("selection")?.let { composition(it, empties) } ?: leaf
    }
  }

  /** A selection reference, or a logical composition of them, as `{param, empty}` leaves. */
  private fun composition(node: VegaValue, empties: Map<String, Boolean>): VegaValue =
    logical(node) { leaf ->
      val name = (leaf as? VegaValue.Str)?.value ?: return@logical leaf
      obj {
        put("param", name)
        put("empty", empties[name] ?: true)
      }
    }

  /**
   * Walks `and`/`or`/`not` and applies [leaf] to everything else.
   *
   * Upstream's `normalizeLogicalComposition`. The operators nest arbitrarily, and a composition is
   * recognised by carrying exactly one of them.
   */
  private fun logical(node: VegaValue?, leaf: (VegaValue) -> VegaValue): VegaValue {
    val value = node ?: return VegaValue.Null
    val operand = value as? VegaValue.Obj ?: return leaf(value)
    (operand.fields["and"] as? VegaValue.Arr)?.let { branch ->
      return obj { put("and", arr(branch.values.map { logical(it, leaf) })) }
    }
    (operand.fields["or"] as? VegaValue.Arr)?.let { branch ->
      return obj { put("or", arr(branch.values.map { logical(it, leaf) })) }
    }
    operand.fields["not"]?.let { branch ->
      return obj { put("not", logical(branch, leaf)) }
    }
    return leaf(value)
  }

  private fun renameToParam(node: VegaValue.Obj): VegaValue.Obj =
    VegaValue.Obj(
      LinkedHashMap<String, VegaValue>().apply {
        node.fields.forEach { (key, value) -> if (key != "selection") this[key] = value }
        node.fields["selection"]?.let { this["param"] = it }
      }
    )

  private fun mentionsSelection(node: VegaValue): Boolean =
    when (node) {
      is VegaValue.Obj ->
        node.fields.containsKey("selection") || node.fields.values.any { mentionsSelection(it) }
      is VegaValue.Arr -> node.values.any { mentionsSelection(it) }
      else -> false
    }
}
