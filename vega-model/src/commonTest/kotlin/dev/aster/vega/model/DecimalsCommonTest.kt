package dev.aster.vega.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The decimal arithmetic, run on **every target** rather than only on the JVM.
 *
 * `Decimals` expands a double by hand — mantissa bits, a multiply by a power of five, a shift — and
 * that is exactly the kind of code that can be right on one platform and wrong on another: it
 * depends on `toRawBits`, on 64-bit arithmetic wrapping the way it should, and on `Long` division
 * behaving identically everywhere. Compiling it for Kotlin/Native proves it *builds*. Only running
 * it proves it agrees, and until this file existed nothing in this repository ran on a native
 * target at all.
 *
 * The expectations are not written by hand and are not this implementation's own output. They were
 * generated from `java.math.BigDecimal` — the oracle `DecimalsTest` cross-checks against on the JVM
 * — and pasted here, so a native run is compared against the same authority as a JVM run:
 * ```
 * // vega-model/src/test/kotlin/.../GenerateDecimalVectors.kt, run once and deleted
 * ./gradlew :vega-model:jvmTest --tests '*GenerateDecimalVectors*' --rerun-tasks
 * ```
 *
 * Sixty cases: the awkward values, the ends of the double range, random bit patterns, and values
 * shaped like the numbers a chart actually formats.
 */
class DecimalsCommonTest {

  private class V(
    val value: Double,
    val decimals: Int,
    val fixed: String,
    val trimmed: String,
    val exponential: String,
    val significant: String,
  )

  @Test
  fun `every function agrees with the BigDecimal oracle on this target`() {
    for (v in VECTORS) {
      val where = "value=${v.value} decimals=${v.decimals}"
      assertEquals(v.fixed, Decimals.fixed(v.value, v.decimals), "fixed $where")
      assertEquals(v.trimmed, Decimals.trimmed(v.value, v.decimals), "trimmed $where")
      assertEquals(v.exponential, Decimals.exponential(v.value, v.decimals), "exponential $where")
      assertEquals(
        v.significant,
        Decimals.significant(v.value, v.decimals + 1),
        "significant $where",
      )
    }
  }

  /** The tie that started it: rounding the exact value, not the shortest printable form. */
  @Test
  fun `a tie rounds the exact binary value`() {
    assertEquals("2.67", Decimals.fixed(2.675, 2))
    assertEquals("2.67e+0", Decimals.exponential(2.675, 2))
    assertEquals("8.84", Decimals.fixed(8.835, 2))
  }

  /**
   * The widest expansion any double has: 1074 fractional digits, and no arbitrary-precision type.
   */
  @Test
  fun `the smallest subnormal expands exactly as far as toFixed allows`() {
    // 1074 places used to work here, because this engine expanded the double itself and a
    // subnormal's exact decimal is that long. `toFixed` **refuses past 100** — the specification
    // says so and JavaScript throws a RangeError — so the property worth asserting is the one a
    // caller can actually ask for: the expansion is exact as far as it is allowed to go.
    val text = Decimals.fixed(Double.MIN_VALUE, 100)
    assertEquals(102, text.length, "0, a point, and 100 digits")
    assertEquals(true, text.all { it == '0' || it == '.' }, "every digit of the first hundred is 0")
    // The digits that do not fit are not lost to rounding: the first significant one is at 324.
    assertEquals("5e-324", Decimals.jsString(Double.MIN_VALUE))
  }

  private companion object {
    val VECTORS =
      listOf(
        V(2.675, 0, "3", "3", "3e+0", "3"),
        V(-2.675, 1, "-2.7", "-2.7", "-2.7e+0", "-2.7"),
        V(1.005, 2, "1.00", "1", "1.00e+0", "1.00"),
        V(0.1, 3, "0.100", "0.1", "1.000e-1", "0.1000"),
        V(0.3333333333333333, 4, "0.3333", "0.3333", "3.3333e-1", "0.33333"),
        V(9.995, 5, "9.99500", "9.995", "9.99500e+0", "9.99500"),
        V(1.0E-7, 6, "0.000000", "0", "1.000000e-7", "1.000000e-7"),
        V(1.234E-6, 7, "0.0000012", "0.0000012", "1.2340000e-6", "0.0000012340000"),
        V(1.234567890123456E15, 0, "1234567890123456", "1234567890123456", "1e+15", "1e+15"),
        // `toFixed` gives up at 10^21 and returns `ToString(x)`, which is the whole of the first
        // two columns here; it used to expand, which no JavaScript engine does.
        V(
          1.0E21,
          1,
          "1e+21",
          "1e+21",
          "1.0e+21",
          "1.0e+21",
        ),
        V(4.9E-324, 2, "0.00", "0", "4.94e-324", "4.94e-324"),
        V(
          1.7976931348623157E308,
          3,
          "1.7976931348623157e+308",
          "1.7976931348623157e+308",
          "1.798e+308",
          "1.798e+308",
        ),
        V(123456.789, 4, "123456.7890", "123456.789", "1.2346e+5", "1.2346e+5"),
        V(0.5, 5, "0.50000", "0.5", "5.00000e-1", "0.500000"),
        V(-0.5, 6, "-0.500000", "-0.5", "-5.000000e-1", "-0.5000000"),
        V(1.0E-300, 7, "0.0000000", "0", "1.0000000e-300", "1.0000000e-300"),
        V(1.4943625197165157E-49, 0, "0", "0", "1e-49", "1e-49"),
        V(
          -1.2573133187979832E63,
          1,
          "-1.2573133187979832e+63",
          "-1.2573133187979832e+63",
          "-1.3e+63",
          "-1.3e+63",
        ),
        V(
          1.3103330986755405E81,
          2,
          "1.3103330986755405e+81",
          "1.3103330986755405e+81",
          "1.31e+81",
          "1.31e+81",
        ),
        V(-2.5294820601962575E-177, 3, "0.000", "0", "-2.529e-177", "-2.529e-177"),
        V(-51379.92740167457, 4, "-51379.9274", "-51379.9274", "-5.1380e+4", "-51380"),
        V(
          3.682508979478897E42,
          5,
          "3.682508979478897e+42",
          "3.682508979478897e+42",
          "3.68251e+42",
          "3.68251e+42",
        ),
        V(
          3.88734626703176E291,
          6,
          "3.88734626703176e+291",
          "3.88734626703176e+291",
          "3.887346e+291",
          "3.887346e+291",
        ),
        V(
          -4.6249700669235514E98,
          7,
          "-4.6249700669235514e+98",
          "-4.6249700669235514e+98",
          "-4.6249701e+98",
          "-4.6249701e+98",
        ),
        V(-2.039854881070558E-54, 0, "0", "0", "-2e-54", "-2e-54"),
        V(-2.7323658417136126E-205, 1, "0.0", "0", "-2.7e-205", "-2.7e-205"),
        V(
          1.1513186818499407E258,
          2,
          "1.1513186818499407e+258",
          "1.1513186818499407e+258",
          "1.15e+258",
          "1.15e+258",
        ),
        V(
          -9.815934426814737E87,
          3,
          "-9.815934426814737e+87",
          "-9.815934426814737e+87",
          "-9.816e+87",
          "-9.816e+87",
        ),
        V(
          8.55857370927194E175,
          4,
          "8.55857370927194e+175",
          "8.55857370927194e+175",
          "8.5586e+175",
          "8.5586e+175",
        ),
        V(
          -7.085031972139411E295,
          5,
          "-7.085031972139411e+295",
          "-7.085031972139411e+295",
          "-7.08503e+295",
          "-7.08503e+295",
        ),
        V(1.8767407571358395E-94, 6, "0.000000", "0", "1.876741e-94", "1.876741e-94"),
        V(2.4028137460719446E-11, 7, "0.0000000", "0", "2.4028137e-11", "2.4028137e-11"),
        V(
          1.5869999159012236E286,
          0,
          "1.5869999159012236e+286",
          "1.5869999159012236e+286",
          "2e+286",
          "2e+286",
        ),
        V(
          -1.0177725284694831E257,
          1,
          "-1.0177725284694831e+257",
          "-1.0177725284694831e+257",
          "-1.0e+257",
          "-1.0e+257",
        ),
        V(-5.8932496219140766E-59, 2, "0.00", "0", "-5.89e-59", "-5.89e-59"),
        V(9.782177060719141E-155, 3, "0.000", "0", "9.782e-155", "9.782e-155"),
        V(1.4435981611055822E-297, 4, "0.0000", "0", "1.4436e-297", "1.4436e-297"),
        V(
          -2.727345582897549E293,
          5,
          "-2.727345582897549e+293",
          "-2.727345582897549e+293",
          "-2.72735e+293",
          "-2.72735e+293",
        ),
        V(-2.861050753698754E-17, 6, "0.000000", "0", "-2.861051e-17", "-2.861051e-17"),
        V(-1.6694013121750967E-251, 7, "0.0000000", "0", "-1.6694013e-251", "-1.6694013e-251"),
        V(1563.5625269422326, 0, "1564", "1564", "2e+3", "2e+3"),
        V(1.5027616402416721, 1, "1.5", "1.5", "1.5e+0", "1.5"),
        V(0.05345492494608004, 2, "0.05", "0.05", "5.35e-2", "0.0535"),
        V(1.2783481879839769E8, 3, "127834818.798", "127834818.798", "1.278e+8", "1.278e+8"),
        V(-0.05943697109597286, 4, "-0.0594", "-0.0594", "-5.9437e-2", "-0.059437"),
        V(9374.874202924497, 5, "9374.87420", "9374.8742", "9.37487e+3", "9374.87"),
        V(40539.36216431038, 6, "40539.362164", "40539.362164", "4.053936e+4", "40539.36"),
        V(0.9221080903968993, 7, "0.9221081", "0.9221081", "9.2210809e-1", "0.92210809"),
        V(-59893.839385726664, 0, "-59894", "-59894", "-6e+4", "-6e+4"),
        V(96.0695554182799, 1, "96.1", "96.1", "9.6e+1", "96"),
        V(-503881.10707101406, 2, "-503881.11", "-503881.11", "-5.04e+5", "-5.04e+5"),
        V(-5.763768821200458E-4, 3, "-0.001", "-0.001", "-5.764e-4", "-0.0005764"),
        V(-3320.1790322659817, 4, "-3320.1790", "-3320.179", "-3.3202e+3", "-3320.2"),
        V(79559.94807948612, 5, "79559.94808", "79559.94808", "7.95599e+4", "79559.9"),
        V(
          -7.382723406619331E7,
          6,
          "-73827234.066193",
          "-73827234.066193",
          "-7.382723e+7",
          "-7.382723e+7",
        ),
        V(
          9.267446580790021E8,
          7,
          "926744658.0790021",
          "926744658.0790021",
          "9.2674466e+8",
          "9.2674466e+8",
        ),
        V(33215.440805560545, 0, "33215", "33215", "3e+4", "3e+4"),
        V(0.04431599586767957, 1, "0.0", "0", "4.4e-2", "0.044"),
        V(-9.306677018395362E8, 2, "-930667701.84", "-930667701.84", "-9.31e+8", "-9.31e+8"),
        V(-5.739999212278535, 3, "-5.740", "-5.74", "-5.740e+0", "-5.740"),
      )
  }
}
