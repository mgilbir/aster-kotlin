package dev.aster.vega.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VegaValueTest {

  private val datum =
    VegaValue.Obj(
      linkedMapOf(
        "a" to VegaValue.Num(1.0),
        "nested" to VegaValue.Obj(linkedMapOf("b" to VegaValue.Str("deep"))),
        "list" to
          VegaValue.Arr(listOf(VegaValue.Num(10.0), VegaValue.Num(20.0), VegaValue.Num(30.0))),
        "odd name" to VegaValue.Bool(true),
        "a.b" to VegaValue.Str("escaped"),
      )
    )

  @Test
  fun `resolves simple nested and indexed field paths`() {
    assertEquals(VegaValue.Num(1.0), datum.field("a"))
    assertEquals(VegaValue.Str("deep"), datum.field("nested.b"))
    assertEquals(VegaValue.Num(20.0), datum.field("list[1]"))
    assertEquals(VegaValue.Bool(true), datum.field("[\"odd name\"]"))
    assertEquals(VegaValue.Str("escaped"), datum.field("a\\.b"))
  }

  @Test
  fun `missing segments resolve to null rather than throwing`() {
    assertEquals(VegaValue.Null, datum.field("missing"))
    assertEquals(VegaValue.Null, datum.field("nested.missing"))
    assertEquals(VegaValue.Null, datum.field("a.b.c.d"))
    assertEquals(VegaValue.Null, datum.field("list[99]"))
    assertEquals(VegaValue.Null, datum.field("list[notANumber]"))
  }

  @Test
  fun `unterminated bracket is treated as literal text instead of throwing`() {
    // The remainder becomes its own segment, so the lookup simply misses.
    assertEquals(listOf("list", "[1"), parseFieldPath("list[1"))
    assertEquals(VegaValue.Null, datum.field("list[1"))
  }

  @Test
  fun `empty path resolves to no segments`() {
    assertEquals(emptyList<String>(), parseFieldPath(""))
  }

  /**
   * Every expectation below is `splitAccessPath(p)` from the pinned `vega-util`, read out rather
   * than reasoned about. A `.` is a separator only when there is something in front of it, which is
   * what makes a bracket followed by a dot three segments instead of four — the divergence that
   * made `coordinates[0].lat` resolve to nothing at all.
   */
  @Test
  fun `a separator with nothing in front of it separates nothing`() {
    assertEquals(listOf("list", "1", "b"), parseFieldPath("list[1].b"))
    assertEquals(listOf("a", "0", "b", "c"), parseFieldPath("a[0].b.c"))
    assertEquals(listOf("a", "b"), parseFieldPath("a..b"))
    assertEquals(listOf("a"), parseFieldPath(".a"))
    assertEquals(listOf("a"), parseFieldPath("a."))
    assertEquals(listOf("a", "0", "1"), parseFieldPath("a[0][1]"))
    assertEquals(listOf("0"), parseFieldPath("[0]"))
  }

  @Test
  fun `a bracket ends where its quote ends, not at the next closing bracket`() {
    // `a["b]c"]` is one field named `b]c`; a scan for the next `]` would cut it in half.
    assertEquals(listOf("a", "b]c"), parseFieldPath("a[\"b]c\"]"))
    assertEquals(listOf("x", "a.b", "c"), parseFieldPath("x[\"a.b\"].c"))
    assertEquals(listOf("a", "b"), parseFieldPath("a['b']"))
    // A quote counts as one only immediately after its bracket, so the spaces here are skipped
    // and a quote anywhere else is an ordinary character.
    assertEquals(listOf("a", "b"), parseFieldPath("a[ \"b\" ]"))
    // An empty bracket is an empty segment upstream, not an absent one.
    assertEquals(listOf("a", ""), parseFieldPath("a[]"))
  }

  @Test
  fun `an unterminated quote is literal text, where upstream throws`() {
    assertEquals(listOf("a", "[\"b"), parseFieldPath("a[\"b"))
    // Upstream rejects a top-level quoted path outright; here it simply misses.
    assertEquals(listOf("\"a\".b"), parseFieldPath("\"a\".b"))
  }

  /**
   * Upstream errors on a `]` with no bracket open. Dropping what came before it — which is what
   * upstream's own `i = j + 1` would do here — would turn `a]b` into a read of a field genuinely
   * named `b`, so the character stays in the segment and the lookup misses instead.
   */
  @Test
  fun `a closing bracket with nothing open is an ordinary character`() {
    assertEquals(listOf("a]b"), parseFieldPath("a]b"))
    assertEquals(listOf("0", "]"), parseFieldPath("[0]]"))
    assertEquals(VegaValue.Null, datum.field("list]0"))
  }

  @Test
  fun `an escape survives at the end of a path`() {
    assertEquals(listOf("a"), parseFieldPath("a\\"))
    assertEquals(listOf("a.b"), parseFieldPath("a\\.b"))
  }

  @Test
  fun `a bracket followed by a dot reads the field behind it`() {
    val rows =
      VegaValue.Obj(
        linkedMapOf(
          "coordinates" to
            VegaValue.Arr(listOf(VegaValue.Obj(linkedMapOf("lat" to VegaValue.Num(52.37)))))
        )
      )
    assertEquals(VegaValue.Num(52.37), rows.field("coordinates[0].lat"))
  }

  @Test
  fun `numeric coercion follows vega rules`() {
    assertEquals(1.0, VegaValue.Bool(true).asDouble())
    assertEquals(0.0, VegaValue.Bool(false).asDouble())
    assertEquals(42.0, VegaValue.Str(" 42 ").asDouble())
    assertEquals(5.0, VegaValue.Arr(listOf(VegaValue.Num(5.0))).asDouble())
    assertTrue(VegaValue.Str("abc").asDouble().isNaN())
    assertTrue(VegaValue.Null.asDouble().isNaN())
    assertTrue(VegaValue.EmptyObject.asDouble().isNaN())
    assertTrue(VegaValue.Arr(listOf(VegaValue.Num(1.0), VegaValue.Num(2.0))).asDouble().isNaN())
  }

  @Test
  fun `string coercion uses canonical number formatting`() {
    assertEquals("1", VegaValue.Num(1.0).asString())
    assertEquals("1.5", VegaValue.Num(1.5).asString())
    assertEquals("0", VegaValue.Num(-0.0).asString())
    assertEquals("null", VegaValue.Null.asString())
  }

  @Test
  fun `truthiness matches vega`() {
    assertFalse(VegaValue.Null.asBoolean())
    assertFalse(VegaValue.Num(0.0).asBoolean())
    assertFalse(VegaValue.Num(Double.NaN).asBoolean())
    assertFalse(VegaValue.Str("").asBoolean())
    assertTrue(VegaValue.Str("0").asBoolean())
    assertTrue(VegaValue.EmptyArray.asBoolean())
    assertTrue(VegaValue.EmptyObject.asBoolean())
  }

  @Test
  fun `isMissing covers null and NaN only`() {
    assertTrue(VegaValue.Null.isMissing)
    assertTrue(VegaValue.Num(Double.NaN).isMissing)
    assertFalse(VegaValue.Num(0.0).isMissing)
    assertFalse(VegaValue.Str("").isMissing)
  }
}
