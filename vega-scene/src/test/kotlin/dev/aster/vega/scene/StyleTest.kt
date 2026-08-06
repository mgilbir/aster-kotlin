package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StyleTest {

  @ParameterizedTest
  @CsvSource(
    "#000, #000000",
    "#fff, #ffffff",
    "#4682b4, #4682b4",
    "#4682B4, #4682b4",
    "steelblue, #4682b4",
    "STEELBLUE, #4682b4",
    "rgb(70 130 180), #4682b4",
    "'rgb(70, 130, 180)', #4682b4",
  )
  fun `parses hex named and rgb colours to a canonical hex form`(input: String, expected: String) {
    val color = requireNotNull(SceneColor.parse(input)) { "failed to parse $input" }
    assertEquals(expected, color.toCssHex())
  }

  @Test
  fun `alpha survives parsing and serialization`() {
    val short = requireNotNull(SceneColor.parse("#0f08"))
    assertEquals("#00ff0088", short.toCssHex())

    val long = requireNotNull(SceneColor.parse("#00ff0080"))
    assertEquals(0.5019607843137255, long.alpha, 1e-9)
  }

  @Test
  fun `none and transparent map to a transparent colour`() {
    assertTrue(requireNotNull(SceneColor.parse("none")).isTransparent)
    assertTrue(requireNotNull(SceneColor.parse("transparent")).isTransparent)
  }

  @Test
  fun `unknown colour names fail instead of defaulting to black`() {
    assertNull(SceneColor.parse("chartreusey"))
    assertNull(SceneColor.parse("#12345"))
    assertNull(SceneColor.parse("#gggggg"))
    assertNull(SceneColor.parse(""))
  }

  @Test
  fun `argb round-trips`() {
    val original = SceneColor(0.25, 0.5, 0.75, 0.5)
    val roundTripped = SceneColor.fromArgb(original.toArgb())
    assertEquals(original.red, roundTripped.red, 1.0 / 255.0)
    assertEquals(original.green, roundTripped.green, 1.0 / 255.0)
    assertEquals(original.blue, roundTripped.blue, 1.0 / 255.0)
    assertEquals(original.alpha, roundTripped.alpha, 1.0 / 255.0)
  }

  @Test
  fun `argb packs alpha in the high byte`() {
    assertEquals(0xFF000000.toInt(), SceneColor.Black.toArgb())
    assertEquals(0xFFFFFFFF.toInt(), SceneColor.White.toArgb())
    assertEquals(0x00000000, SceneColor.Transparent.toArgb())
  }

  @Test
  fun `out of range components are clamped when packing`() {
    val overshoot = SceneColor(2.0, -1.0, 0.5, 1.0)
    assertEquals("#ff0080", overshoot.toCssHex())
  }

  @Test
  fun `withAlpha clamps into range`() {
    assertEquals(1.0, SceneColor.Black.withAlpha(5.0).alpha)
    assertEquals(0.0, SceneColor.Black.withAlpha(-5.0).alpha)
  }

  @Test
  fun `zero width or zero opacity stroke is invisible`() {
    val paint = ScenePaint.Black
    assertTrue(!Stroke(paint, width = 0.0).isVisible)
    assertTrue(!Stroke(paint, width = 1.0, opacity = 0.0).isVisible)
    assertTrue(Stroke(paint, width = 1.0).isVisible)
    assertEquals(1.5, Stroke(paint, width = 3.0).halfWidth)
  }

  @Test
  fun `non-finite colour components are rejected at construction`() {
    val failure =
      org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
        SceneColor(Double.NaN, 0.0, 0.0)
      }
    assertTrue(failure.message!!.contains("finite"))
  }
}
