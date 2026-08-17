package dev.aster.vega.scene

/**
 * The scene read through accessors a **foreign renderer** can actually call.
 *
 * A renderer written in Swift or Objective-C reaches this engine through the Obj-C framework Kotlin
 * exports, and that boundary cannot see everything Kotlin can. The one that matters is
 * [ScenePaint.Solid]: it is a `@JvmInline value class`, and a value class implementing an interface
 * has no Obj-C representation, so it is **absent from the generated header entirely**. From Swift a
 * fill therefore appears as an opaque `ScenePaint` with no way to ask the only question worth
 * asking — what colour is it — and nearly every fill in a chart is a solid one.
 *
 * The answer is not to reshape the scene for one platform's benefit. `Solid` stays a value class,
 * because a fill is allocated once per mark per frame and that is where the allocation would land.
 * Instead the questions a renderer asks are given plain functions, which the boundary does
 * understand.
 *
 * These are equally callable from Kotlin, and the Kotlin renderers use them too — one description
 * of "what colour is this" is better than two that could drift.
 */
public object ForeignPaint {

  /** The colour of a solid paint, or null when it is a gradient. */
  public fun solidColor(paint: ScenePaint?): SceneColor? = (paint as? ScenePaint.Solid)?.color

  /**
   * A fill's colour with its own opacity already multiplied in, or null when it paints nothing.
   *
   * The multiplication belongs here rather than in each renderer: `fillOpacity` and the colour's
   * own alpha are separate in a specification and the same thing on a surface, and a renderer that
   * forgot one would draw a mark that is too solid rather than fail.
   */
  public fun solidFill(fill: Fill?): SceneColor? {
    val colour = solidColor(fill?.paint) ?: return null
    val opacity = fill?.opacity ?: 1.0
    return colour.withAlpha(colour.alpha * opacity)
  }

  /** As [solidFill], for a stroke. */
  public fun solidStroke(stroke: Stroke?): SceneColor? {
    val colour = solidColor(stroke?.paint) ?: return null
    val opacity = stroke?.opacity ?: 1.0
    return colour.withAlpha(colour.alpha * opacity)
  }

  /** Whether a paint is a gradient, which a foreign renderer has to handle separately. */
  public fun isGradient(paint: ScenePaint?): Boolean =
    paint is ScenePaint.LinearGradient || paint is ScenePaint.RadialGradient

  /**
   * A linear gradient's own fields, or null; the class *is* exported, so this is only for symmetry.
   */
  public fun linearGradient(paint: ScenePaint?): ScenePaint.LinearGradient? =
    paint as? ScenePaint.LinearGradient

  public fun radialGradient(paint: ScenePaint?): ScenePaint.RadialGradient? =
    paint as? ScenePaint.RadialGradient
}

/**
 * A path command read without pattern matching, which the Obj-C boundary cannot express.
 *
 * Swift sees `PathCommand` as a protocol and each variant as a class, so a `switch` over them means
 * a chain of `as?` casts and a re-read of the field names. [kind] plus the six coordinates says the
 * same thing in a shape a renderer can loop over.
 */
public object ForeignPath {

  /** `move`, `line`, `cubic` or `close`. */
  public fun kind(command: PathCommand): String =
    when (command) {
      is PathCommand.MoveTo -> "move"
      is PathCommand.LineTo -> "line"
      is PathCommand.CubicTo -> "cubic"
      PathCommand.Close -> "close"
    }

  /** The command's endpoint; zero for a close, which has none. */
  public fun x(command: PathCommand): Double =
    when (command) {
      is PathCommand.MoveTo -> command.x
      is PathCommand.LineTo -> command.x
      is PathCommand.CubicTo -> command.x
      PathCommand.Close -> 0.0
    }

  public fun y(command: PathCommand): Double =
    when (command) {
      is PathCommand.MoveTo -> command.y
      is PathCommand.LineTo -> command.y
      is PathCommand.CubicTo -> command.y
      PathCommand.Close -> 0.0
    }

  /** The first control point of a cubic; zero for anything else. */
  public fun x1(command: PathCommand): Double = (command as? PathCommand.CubicTo)?.x1 ?: 0.0

  public fun y1(command: PathCommand): Double = (command as? PathCommand.CubicTo)?.y1 ?: 0.0

  public fun x2(command: PathCommand): Double = (command as? PathCommand.CubicTo)?.x2 ?: 0.0

  public fun y2(command: PathCommand): Double = (command as? PathCommand.CubicTo)?.y2 ?: 0.0
}

/**
 * Which kind of node this is, as a string a foreign renderer can switch on.
 *
 * The same argument as [ForeignPath.kind]: the classes are exported and castable, but a walk
 * written against them in Swift is a ladder of casts that has to be kept in step with the sealed
 * hierarchy by hand. Asking Kotlin — which the compiler checks exhaustively — is harder to get out
 * of step.
 */
public fun SceneNode.foreignKind(): String =
  when (this) {
    is GroupNode -> "group"
    is RectNode -> "rect"
    is RuleNode -> "rule"
    is PathNode -> "path"
    is SymbolNode -> "symbol"
    is TextNode -> "text"
    is ImageNode -> "image"
  }
