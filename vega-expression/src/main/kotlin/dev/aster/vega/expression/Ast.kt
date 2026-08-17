package dev.aster.vega.expression

import dev.aster.vega.model.VegaValue

/**
 * Abstract syntax tree for a Vega expression.
 *
 * Immutable and free of any evaluation state, so one parsed expression can be evaluated against
 * many datums concurrently and cached by source text.
 */
public sealed interface Node {

  public data class Literal(val value: VegaValue) : Node

  /** A bare name: `datum`, a signal, a constant like `PI`, or a function being referenced. */
  public data class Identifier(val name: String) : Node

  /**
   * `object.property` or `object[expression]`.
   *
   * @param computed true for the bracket form, where [property] is an expression rather than a
   *   name.
   */
  public data class Member(val target: Node, val property: Node, val computed: Boolean) : Node

  public data class Call(val callee: Node, val arguments: List<Node>) : Node

  public data class Unary(val operator: String, val operand: Node) : Node

  public data class Binary(val operator: String, val left: Node, val right: Node) : Node

  /** `&&` and `||`, which short-circuit and so cannot be folded into [Binary]. */
  public data class Logical(val operator: String, val left: Node, val right: Node) : Node

  public data class Conditional(val test: Node, val consequent: Node, val alternate: Node) : Node

  public data class ArrayLiteral(val elements: List<Node>) : Node

  public data class ObjectLiteral(val entries: List<Pair<Node, Node>>) : Node
}

/** Walks [node] and every descendant, parents before children. */
public fun Node.walk(visit: (Node) -> Unit) {
  visit(this)
  when (this) {
    is Node.Member -> {
      target.walk(visit)
      if (computed) property.walk(visit)
    }
    is Node.Call -> {
      callee.walk(visit)
      arguments.forEach { it.walk(visit) }
    }
    is Node.Unary -> operand.walk(visit)
    is Node.Binary -> {
      left.walk(visit)
      right.walk(visit)
    }
    is Node.Logical -> {
      left.walk(visit)
      right.walk(visit)
    }
    is Node.Conditional -> {
      test.walk(visit)
      consequent.walk(visit)
      alternate.walk(visit)
    }
    is Node.ArrayLiteral -> elements.forEach { it.walk(visit) }
    is Node.ObjectLiteral ->
      entries.forEach { (key, value) ->
        key.walk(visit)
        value.walk(visit)
      }
    is Node.Literal,
    is Node.Identifier -> Unit
  }
}

/**
 * The dotted field path a member chain reads from `datum`, or `null` if it does not start at
 * `datum`.
 *
 * Used to report an expression's field dependencies, which the dataflow needs to know what to
 * invalidate. Only static paths are reported: `datum[someSignal]` has no compile-time path.
 */
public fun Node.datumFieldPath(): String? {
  if (this !is Node.Member) return null
  val segments = mutableListOf<String>()
  var current: Node = this
  while (current is Node.Member) {
    val segment =
      when {
        !current.computed -> (current.property as? Node.Identifier)?.name
        else -> (current.property as? Node.Literal)?.value?.let { literalKey(it) }
      } ?: return null
    segments.add(0, segment)
    current = current.target
  }
  return if (current is Node.Identifier && current.name == "datum") segments.joinToString(".")
  else null
}

private fun literalKey(value: VegaValue): String? =
  when (value) {
    is VegaValue.Str -> value.value
    is VegaValue.Num -> dev.aster.vega.model.canonicalNumberString(value.value)
    else -> null
  }
