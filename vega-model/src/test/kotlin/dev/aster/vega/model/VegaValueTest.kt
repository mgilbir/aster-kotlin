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
