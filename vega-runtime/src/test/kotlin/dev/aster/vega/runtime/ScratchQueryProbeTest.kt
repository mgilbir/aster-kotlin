package dev.aster.vega.runtime

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Test

class ScratchQueryProbeTest {
  @Test
  fun probe() {
    val compiler = CachingExpressionCompiler(VegaExpressionCompiler())
    val scope =
      object : ExpressionScope {
        override val datum: VegaValue =
          VegaValue.Obj(mapOf("job" to VegaValue.Str("Farmer"), "sex" to VegaValue.Str("men")))

        override fun signal(name: String): VegaValue =
          when (name) {
            "query" -> VegaValue.Str("farmer")
            "sex" -> VegaValue.Str("all")
            else -> VegaValue.Null
          }

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
    for (expr in
      listOf(
        "test(regexp('farmer','i'), 'Farmer')",
        "test(regexp(query,'i'), datum.job)",
        "regexp(query,'i')",
        "(sex === 'all' || datum.sex === sex) && (!query || test(regexp(query,'i'), datum.job))",
        "test(regexp('a'), 'cat')",
      )) {
      val result =
        when (val c = compiler.compile(expr)) {
          is ExpressionResult.Failed -> "FAILED ${c.diagnostic.message}"
          is ExpressionResult.Compiled ->
            try {
              c.expression.evaluate(scope).toString()
            } catch (e: Exception) {
              "THREW ${e.message}"
            }
        }
      println("$expr  ->  $result")
    }
  }
}
