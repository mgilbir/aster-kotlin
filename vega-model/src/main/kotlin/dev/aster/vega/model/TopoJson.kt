package dev.aster.vega.model

/**
 * TopoJSON, decoded back into the GeoJSON a map is drawn from.
 *
 * TopoJSON is GeoJSON with the shared boundaries factored out: every coordinate sequence is an
 * *arc*, stored once and referenced by index — negative for "walk it backwards" — and the whole
 * file is usually quantized onto an integer grid with a `transform` that says how to get back. A
 * country file is a third the size and, more usefully, adjacent borders are literally the same arc,
 * so a mesh of internal boundaries can be extracted without drawing each one twice.
 *
 * Ported from `topojson-client/src/{feature,transform,reverse,mesh,stitch}.js`, which is what
 * `vega-loader`'s `topojson` format calls.
 */
public object TopoJson {

  /** `format.filter` for a mesh: which shared arcs to keep. */
  public enum class MeshFilter {
    /** Every arc, drawn once. */
    ALL,

    /** Only the arcs two different geometries share — the internal borders. */
    INTERIOR,

    /** Only the arcs no two geometries share — the coastline. */
    EXTERIOR,
  }

  /**
   * The features of one named object, as `vega-loader` returns them.
   *
   * A `GeometryCollection` becomes its list of features; anything else is a single feature in a
   * list of one, which is upstream's `object.features || [object]`.
   */
  public fun feature(topology: VegaValue, name: String): List<VegaValue>? {
    val objects = topology.field("objects") as? VegaValue.Obj ?: return null
    val target = objects.fields[name] ?: return null
    val decoded = featureOf(topology, target)
    val features = decoded.field("features")
    return if (features is VegaValue.Arr) features.values else listOf(decoded)
  }

  /** The arcs of one named object as a single `MultiLineString`, deduplicated and stitched. */
  public fun mesh(topology: VegaValue, name: String, filter: MeshFilter): List<VegaValue>? {
    val objects = topology.field("objects") as? VegaValue.Obj ?: return null
    val target = objects.fields[name] ?: return null
    val arcs = meshArcs(topology, target, filter)
    return listOf(geometry(topology, obj("type" to str("MultiLineString"), "arcs" to arcs)))
  }

  // ---- features -------------------------------------------------------------

  private fun featureOf(topology: VegaValue, o: VegaValue): VegaValue {
    if (o.field("type").asString() == "GeometryCollection") {
      val geometries = (o.field("geometries") as? VegaValue.Arr)?.values.orEmpty()
      return obj(
        "type" to str("FeatureCollection"),
        "features" to VegaValue.Arr(geometries.map { one(topology, it) }),
      )
    }
    return one(topology, o)
  }

  private fun one(topology: VegaValue, o: VegaValue): VegaValue {
    val id = o.field("id")
    val bbox = o.field("bbox")
    val properties = (o.field("properties") as? VegaValue.Obj) ?: VegaValue.Obj(linkedMapOf())
    val fields = LinkedHashMap<String, VegaValue>()
    fields["type"] = str("Feature")
    // Upstream omits `id` and `bbox` rather than writing nulls, and a `properties` lookup on a
    // feature that has neither should miss rather than find an empty one.
    if (id !is VegaValue.Null) fields["id"] = id
    if (bbox !is VegaValue.Null) fields["bbox"] = bbox
    fields["properties"] = properties
    fields["geometry"] = geometry(topology, o)
    return VegaValue.Obj(fields)
  }

  // ---- geometry -------------------------------------------------------------

  private fun geometry(topology: VegaValue, o: VegaValue): VegaValue {
    val arcs = (topology.field("arcs") as? VegaValue.Arr)?.values.orEmpty()
    val transform = Transform.of(topology.field("transform"))
    return Decoder(arcs, transform).geometry(o)
  }

  /**
   * The quantization a TopoJSON file is usually stored under.
   *
   * Every arc is a run of *deltas* on an integer grid, so a point is the running sum times the
   * scale plus the translation — and the running sum restarts at the beginning of each arc, which
   * is what the `i == 0` test is for. A file with no `transform` holds absolute coordinates.
   */
  private class Transform(
    val kx: Double,
    val ky: Double,
    val dx: Double,
    val dy: Double,
  ) {
    companion object {
      fun of(value: VegaValue): Transform? {
        val obj = value as? VegaValue.Obj ?: return null
        val scale = (obj.fields["scale"] as? VegaValue.Arr)?.values ?: return null
        val translate = (obj.fields["translate"] as? VegaValue.Arr)?.values ?: return null
        if (scale.size < 2 || translate.size < 2) return null
        return Transform(
          scale[0].asDouble(),
          scale[1].asDouble(),
          translate[0].asDouble(),
          translate[1].asDouble(),
        )
      }
    }
  }

  private class Decoder(private val arcs: List<VegaValue>, private val transform: Transform?) {
    private var x0 = 0.0
    private var y0 = 0.0

    fun geometry(o: VegaValue): VegaValue {
      val type = o.field("type").asString()
      val coordinates: VegaValue =
        when (type) {
          "GeometryCollection" -> {
            val parts = (o.field("geometries") as? VegaValue.Arr)?.values.orEmpty()
            return obj(
              "type" to str(type),
              "geometries" to VegaValue.Arr(parts.map { geometry(it) }),
            )
          }
          "Point" -> point(o.field("coordinates"))
          "MultiPoint" -> VegaValue.Arr(list(o.field("coordinates")).map { point(it) })
          "LineString" -> line(o.field("arcs"))
          "MultiLineString" -> VegaValue.Arr(list(o.field("arcs")).map { line(it) })
          "Polygon" -> polygon(o.field("arcs"))
          "MultiPolygon" -> VegaValue.Arr(list(o.field("arcs")).map { polygon(it) })
          else -> return VegaValue.Null
        }
      return obj("type" to str(type), "coordinates" to coordinates)
    }

    private fun polygon(value: VegaValue): VegaValue = VegaValue.Arr(list(value).map { ring(it) })

    private fun ring(value: VegaValue): VegaValue {
      val points = (line(value) as VegaValue.Arr).values.toMutableList()
      // An arc of two points closes into a degenerate ring; upstream pads it rather than emitting
      // something no renderer can read.
      while (points.size < 4 && points.isNotEmpty()) points += points[0]
      return VegaValue.Arr(points)
    }

    private fun line(value: VegaValue): VegaValue {
      val points = mutableListOf<VegaValue>()
      for (index in list(value)) arc(index.asDouble().toInt(), points)
      if (points.size < 2 && points.isNotEmpty()) points += points[0]
      return VegaValue.Arr(points)
    }

    /**
     * One arc appended to [points], reversed when its index is negative.
     *
     * The last point of the previous arc and the first of this one are the same coordinate — the
     * arcs meet — so the previous one is dropped rather than emitted twice.
     */
    private fun arc(index: Int, points: MutableList<VegaValue>) {
      if (points.isNotEmpty()) points.removeAt(points.size - 1)
      val source = list(arcs.getOrNull(if (index < 0) index.inv() else index) ?: VegaValue.Null)
      val n = source.size
      for (k in 0 until n) points += transformed(source[k], k)
      if (index < 0) reverse(points, n)
    }

    private fun point(value: VegaValue): VegaValue = transformed(value, 0)

    /** Reverses the last [n] entries in place, which is how a shared border is walked backwards. */
    private fun reverse(array: MutableList<VegaValue>, n: Int) {
      var j = array.size
      var i = j - n
      while (i < --j) {
        val held = array[i]
        array[i] = array[j]
        array[j] = held
        i++
      }
    }

    private fun transformed(input: VegaValue, index: Int): VegaValue {
      val values = list(input)
      if (transform == null) return VegaValue.Arr(values)
      if (index == 0) {
        x0 = 0.0
        y0 = 0.0
      }
      x0 += values.getOrNull(0)?.asDouble() ?: 0.0
      y0 += values.getOrNull(1)?.asDouble() ?: 0.0
      val out = mutableListOf<VegaValue>()
      out += VegaValue.Num(x0 * transform.kx + transform.dx)
      out += VegaValue.Num(y0 * transform.ky + transform.dy)
      for (j in 2 until values.size) out += values[j]
      return VegaValue.Arr(out)
    }
  }

  // ---- mesh -----------------------------------------------------------------

  /**
   * The arc indices a mesh draws, stitched into runs.
   *
   * Each arc is kept once — the first geometry that referenced it wins — and `filter` decides
   * whether that arc is an internal border (two different geometries) or a coastline (one).
   * Stitching then joins arcs that meet end to end, so a border crossing three countries is one
   * line rather than three.
   */
  private fun meshArcs(topology: VegaValue, o: VegaValue, filter: MeshFilter): VegaValue {
    val byArc = HashMap<Int, MutableList<Pair<Int, VegaValue>>>()
    val order = mutableListOf<Int>()

    fun record(index: Int, owner: VegaValue) {
      val key = if (index < 0) index.inv() else index
      val bucket =
        byArc.getOrPut(key) {
          order += key
          mutableListOf()
        }
      bucket += index to owner
    }

    fun collect(node: VegaValue) {
      when (node.field("type").asString()) {
        "GeometryCollection" -> for (part in list(node.field("geometries"))) collect(part)
        "LineString" -> for (a in list(node.field("arcs"))) record(a.asDouble().toInt(), node)
        "MultiLineString",
        "Polygon" ->
          for (level in list(node.field("arcs"))) {
            for (a in list(level)) record(a.asDouble().toInt(), node)
          }
        "MultiPolygon" ->
          for (outer in list(node.field("arcs"))) {
            for (level in list(outer)) {
              for (a in list(level)) record(a.asDouble().toInt(), node)
            }
          }
        else -> Unit
      }
    }
    collect(o)

    // `order` keeps the arcs in the order they were first seen, which is `geomsByArc.forEach` over
    // a sparse array upstream: the index order, not the encounter order.
    val kept = mutableListOf<Int>()
    for (key in order.sorted()) {
      val geoms = byArc[key] ?: continue
      val keep =
        when (filter) {
          MeshFilter.ALL -> true
          MeshFilter.INTERIOR -> geoms.first().second !== geoms.last().second
          MeshFilter.EXTERIOR -> geoms.first().second === geoms.last().second
        }
      if (keep) kept += geoms.first().first
    }
    return VegaValue.Arr(stitch(topology, kept).map { run -> VegaValue.Arr(run.map { num(it) }) })
  }

  /**
   * Joins arcs that share an endpoint into runs, upstream's `stitch`.
   *
   * Kept because the mesh is a `MultiLineString` and each stitched run is one of its lines: a
   * renderer draws a run as a single path, and the difference shows wherever a line has a dash
   * pattern or a join. The order the runs come out in is upstream's too, and it is not the order
   * they were built in — a fragment whose endpoint another fragment later claimed is *orphaned* out
   * of the index and re-emitted at the end, alone, which is exactly what the exterior mesh of two
   * shapes meeting at the same two points does.
   */
  private fun stitch(topology: VegaValue, arcs: List<Int>): List<List<Int>> {
    val topoArcs = (topology.field("arcs") as? VegaValue.Arr)?.values.orEmpty()
    val quantized = topology.field("transform") !is VegaValue.Null

    fun ends(index: Int): Pair<String, String> {
      val arc = list(topoArcs.getOrNull(if (index < 0) index.inv() else index) ?: VegaValue.Null)
      if (arc.isEmpty()) return "" to ""
      val first = key(arc.first())
      // A quantized arc holds deltas, so its far end is the running sum rather than its last entry.
      val last =
        if (quantized) {
          var sx = 0.0
          var sy = 0.0
          for (delta in arc) {
            val point = list(delta)
            sx += point.getOrNull(0)?.asDouble() ?: 0.0
            sy += point.getOrNull(1)?.asDouble() ?: 0.0
          }
          "$sx,$sy"
        } else {
          key(arc.last())
        }
      return if (index < 0) last to first else first to last
    }

    val byStart = LinkedHashMap<String, Fragment>()
    val byEnd = LinkedHashMap<String, Fragment>()

    // Empty arcs first: an arc of two identical points can be absorbed by any run that touches it,
    // and stitching it later would leave one line split in two.
    val ordered = arcs.toMutableList()
    var emptyIndex = -1
    for (j in ordered.indices) {
      val index = ordered[j]
      val arc = list(topoArcs.getOrNull(if (index < 0) index.inv() else index) ?: VegaValue.Null)
      val second = if (arc.size > 1) list(arc[1]) else emptyList()
      val degenerate =
        arc.size < 3 &&
          (second.getOrNull(0)?.asDouble() ?: 0.0) == 0.0 &&
          (second.getOrNull(1)?.asDouble() ?: 0.0) == 0.0
      if (degenerate) {
        emptyIndex++
        val held = ordered[emptyIndex]
        ordered[emptyIndex] = index
        ordered[j] = held
      }
    }

    for (index in ordered) {
      val (start, end) = ends(index)
      val before = byEnd[start]
      val after = byStart[end]
      when {
        before != null -> {
          byEnd.remove(before.end)
          before.items += index
          before.end = end
          val other = byStart[end]
          val joined =
            if (other == null) {
              before
            } else {
              byStart.remove(other.start)
              if (other === before) before else before.concat(other)
            }
          joined.start = before.start
          joined.end = if (other == null) before.end else other.end
          byStart[joined.start] = joined
          byEnd[joined.end] = joined
        }
        after != null -> {
          byStart.remove(after.start)
          after.items.add(0, index)
          after.start = start
          val other = byEnd[start]
          val joined =
            if (other == null) {
              after
            } else {
              byEnd.remove(other.end)
              if (other === after) after else other.concat(after)
            }
          joined.start = if (other == null) after.start else other.start
          joined.end = after.end
          byStart[joined.start] = joined
          byEnd[joined.end] = joined
        }
        else -> {
          val fragment = Fragment(mutableListOf(index))
          fragment.start = start
          fragment.end = end
          byStart[start] = fragment
          byEnd[end] = fragment
        }
      }
    }

    val fragments = mutableListOf<List<Int>>()
    val stitched = HashSet<Int>()

    fun flush(from: LinkedHashMap<String, Fragment>, other: LinkedHashMap<String, Fragment>) {
      for (fragment in from.values.toList()) {
        other.remove(fragment.start)
        // Cleared so the second pass cannot delete a live entry by a stale endpoint, which is what
        // upstream's `delete f.start` leaves behind.
        fragment.start = ""
        fragment.end = ""
        for (index in fragment.items) stitched += if (index < 0) index.inv() else index
        fragments += fragment.items.toList()
      }
    }
    flush(byEnd, byStart)
    flush(byStart, byEnd)
    for (index in arcs) {
      if ((if (index < 0) index.inv() else index) !in stitched) fragments += listOf(index)
    }
    return fragments
  }

  /** One run of arcs being stitched, with the coordinates its two ends currently sit on. */
  private class Fragment(val items: MutableList<Int>) {
    var start: String = ""
    var end: String = ""

    /** A **new** run, as upstream's `concat` is: the two originals stay orphaned in the index. */
    fun concat(other: Fragment): Fragment = Fragment((items + other.items).toMutableList())
  }

  private fun key(point: VegaValue): String {
    val values = list(point)
    return "${values.getOrNull(0)?.asDouble()},${values.getOrNull(1)?.asDouble()}"
  }

  // ---- small helpers --------------------------------------------------------

  private fun list(value: VegaValue): List<VegaValue> =
    (value as? VegaValue.Arr)?.values ?: emptyList()

  private fun str(value: String): VegaValue = VegaValue.Str(value)

  private fun num(value: Int): VegaValue = VegaValue.Num(value.toDouble())

  private fun obj(vararg pairs: Pair<String, VegaValue>): VegaValue =
    VegaValue.Obj(linkedMapOf(*pairs))
}
