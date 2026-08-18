package dev.aster.vega.model.spec

import dev.aster.vega.model.VegaValue

/**
 * Merges `config` blocks the way upstream's `mergeConfig` does, with later sources winning.
 *
 * This is what lets a **host** theme a chart it did not write. A specification arriving from a
 * server carries the colours that server chose — a `tableau10` scheme picked for a white page, a
 * white point overlay — and an app that draws it on a dark surface has to be able to say otherwise
 * without rewriting the specification. So a host supplies a configuration, the specification's own
 * beats it key by key, and the result is what the compiler reads.
 *
 * The rules are `vega-util`'s `mergeConfig` and `writeConfig`, transcribed rather than
 * approximated, because a merge that is nearly right is a theme that mostly applies:
 * - a **block** is merged property by property, so a host's `axis` and a specification's `axis`
 *   both take effect and only the properties they both name are decided;
 * - an object **inside** a block overwrites rather than merging, with two exceptions upstream
 *   makes: `legend.layout` and every entry of `style` recurse one level further;
 * - `signals` is merged by **name**, the later source's signal replacing an earlier one of the same
 *   name rather than being appended beside it;
 * - and `__proto__`, `constructor` and `prototype` are dropped at every level, which upstream does
 *   because a configuration is often data somebody else wrote.
 *
 * Where this sits in the precedence a chart actually experiences is worth stating, since the merge
 * is only the middle of it: a mark's own encoded property beats every configuration block, a
 * top-level property beats the same key inside `config`, and within the guides a narrower block
 * beats a wider one (`axisXTemporal` over `axisTemporal` over `axisX` over `axisBottom` over
 * `axis`). Those are the compiler's rules and are unchanged; this decides only what the `config`
 * block *is*.
 */
public fun mergeConfig(vararg configs: VegaValue?): VegaValue.Obj? {
  val sources = configs.filterIsInstance<VegaValue.Obj>()
  if (sources.isEmpty()) return null
  val out = LinkedHashMap<String, VegaValue>()
  for (source in sources) {
    for ((key, value) in source.fields) {
      if (!isLegalKey(key)) continue
      if (key == "signals") {
        out["signals"] = mergeNamed(out["signals"], value)
        continue
      }
      // Upstream's recursion constraints, and only these two: the `legend` block recurses into
      // its `layout` entry, and a `style` block recurses into every named style it holds.
      val recurse: ((String) -> Boolean)? =
        when (key) {
          "legend" -> { name ->
            name == "layout"
          }
          "style" -> { _ ->
            true
          }
          else -> null
        }
      writeConfig(out, key, value, recurse)
    }
  }
  return VegaValue.Obj(out)
}

/**
 * One key written into [output], merging an object one level and recursing where [recurse] says to.
 *
 * A non-object simply replaces, which is why a host's `background` is overridden outright by a
 * specification's rather than being combined with it.
 */
private fun writeConfig(
  output: MutableMap<String, VegaValue>,
  key: String,
  value: VegaValue,
  recurse: ((String) -> Boolean)? = null,
) {
  if (!isLegalKey(key)) return
  if (value !is VegaValue.Obj) {
    output[key] = value
    return
  }
  val existing = output[key]
  val merged = LinkedHashMap<String, VegaValue>((existing as? VegaValue.Obj)?.fields ?: emptyMap())
  for ((inner, innerValue) in value.fields) {
    if (recurse != null && recurse(inner)) {
      writeConfig(merged, inner, innerValue)
    } else if (isLegalKey(inner)) {
      merged[inner] = innerValue
    }
  }
  output[key] = VegaValue.Obj(merged)
}

/** `config.signals`: a list where a name appearing twice is one signal, the later one winning. */
private fun mergeNamed(existing: VegaValue?, source: VegaValue): VegaValue {
  val out = LinkedHashMap<String, VegaValue>()
  fun add(list: VegaValue?) {
    (list as? VegaValue.Arr)?.values?.forEach { entry ->
      val name = ((entry as? VegaValue.Obj)?.fields?.get("name") as? VegaValue.Str)?.value
      if (name != null) out[name] = entry else out["$" + out.size] = entry
    }
  }
  add(existing)
  add(source)
  return VegaValue.Arr(out.values.toList())
}

/**
 * Keys a configuration may not carry.
 *
 * Upstream refuses these because a `config` is frequently data from somewhere else, and in
 * JavaScript writing `__proto__` through a merge is a way to reach the prototype chain. Kotlin has
 * no such hole, and they are refused anyway: the merged object is handed back to a caller that may
 * serialise it into a JavaScript host, and a value this engine passed through would then arrive
 * there.
 */
private fun isLegalKey(key: String): Boolean =
  key != "__proto__" && key != "constructor" && key != "prototype"
