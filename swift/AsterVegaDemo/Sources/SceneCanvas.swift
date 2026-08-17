import AsterVega
import CoreGraphics
import SwiftUI

/// Draws a compiled scene into a SwiftUI `Canvas`.
///
/// The whole of the drawing is `SceneWalk` into a `CoreGraphicsTarget` — the same two types the
/// package's tests exercise, so what appears here is what those tests assert about.
///
/// **No flip.** SwiftUI's canvas already has its origin at the top left with y growing down, which is
/// the space a scene is in, so a chart drawn straight into it is the right way up. The bitmap tests in
/// the package *do* flip, because a `CGBitmapContext` on its own has its origin at the bottom left —
/// that difference belongs to the caller, which is why the renderer does not flip anything itself.
struct SceneCanvas: View {
  let scene: AsterVega.Scene
  /// Where a touch goes. Nil for a chart that is only being looked at.
  var session: ChartSession?

  /// The canvas's size, remembered so a gesture can undo the same placement the drawing used.
  @State private var canvasSize: CGSize = .zero
  /// So `-tap` fires once rather than on every layout pass.
  @State private var tapped = false

  var body: some View {
    Canvas { graphics, size in
      graphics.withCGContext { context in
        draw(into: context, size: size)
      }
    }
    // A scene has a declared size and an aspect ratio worth keeping; the canvas fills what it is
    // given and the drawing is centred inside it.
    .aspectRatio(aspect, contentMode: .fit)
    .background(
      // The canvas's own size, which a gesture needs and `Canvas` does not otherwise expose. Read from
      // a background reader rather than guessed, because the placement a touch is inverted through has
      // to be *the* placement the drawing used or every tap misses by the difference.
      GeometryReader { proxy in
        Color.clear
          .onAppear { report(proxy.size) }
          .onChange(of: proxy.size) { _, new in report(new) }
      }
    )
    .gesture(touch)
  }

  private func report(_ size: CGSize) {
    canvasSize = size
    guard let session, let placement = placement(in: size) else { return }
    session.place(
      contentScale: placement.scale,
      viewport: Rect(
        x: 0, y: 0,
        width: Double(size.width) / placement.scale,
        height: Double(size.height) / placement.scale
      )
    )
    performLaunchTap(placement: placement)
  }

  /// `-tap x,y` taps the chart at a point in **scene** coordinates once it has been placed.
  ///
  /// The simulator can launch an app but cannot tap it, so a screenshot of a chart *after* a touch is
  /// otherwise impossible to script. It deliberately goes the long way round — scene point to canvas
  /// location, then back through `scenePoint(of:)` — so what it exercises is the same inversion a finger
  /// does, rather than a shortcut that could pass while real taps miss.
  private func performLaunchTap(placement: (scale: Double, left: CGFloat, top: CGFloat)) {
    guard !tapped, let session,
      let argument = UserDefaults.standard.string(forKey: "tap")
    else { return }
    let halves = argument.split(separator: ",")
    guard halves.count == 2, let sceneX = Double(halves[0]), let sceneY = Double(halves[1]) else {
      return
    }
    tapped = true
    let location = CGPoint(
      x: CGFloat(sceneX) * CGFloat(placement.scale) + placement.left,
      y: CGFloat(sceneY) * CGFloat(placement.scale) + placement.top
    )
    if let point = scenePoint(of: location) { session.tap(at: point) }
  }

  private var touch: some Gesture {
    // `DragGesture(minimumDistance: 0)` rather than `TapGesture`, because a tap gesture reports *that*
    // a tap happened and not where — and the whole question here is where.
    DragGesture(minimumDistance: 0)
      .onEnded { value in
        guard let session, let point = scenePoint(of: value.location) else { return }
        session.tap(at: point)
      }
  }

  /// A point in the canvas turned into the chart's own surface coordinates.
  ///
  /// The inverse of exactly what `draw` does: subtract the centring offset, then divide by the fit
  /// scale — except the division is the controller's job, since `contentScale` is part of its contract.
  /// So this hands over a point in scaled surface space with the offset removed, which is what the
  /// Android view does with its padding.
  private func scenePoint(of location: CGPoint) -> Point? {
    guard let placement = placement(in: canvasSize) else { return nil }
    return Point(
      x: Double(location.x - placement.left),
      y: Double(location.y - placement.top)
    )
  }

  /// The fit scale and the centring offset, computed once and used by both the drawing and the touches.
  private func placement(in size: CGSize) -> (scale: Double, left: CGFloat, top: CGFloat)? {
    guard scene.width > 0, scene.height > 0, size.width > 0, size.height > 0 else { return nil }
    let scale = min(size.width / CGFloat(scene.width), size.height / CGFloat(scene.height))
    return (
      scale: Double(scale),
      left: (size.width - CGFloat(scene.width) * scale) / 2,
      top: (size.height - CGFloat(scene.height) * scale) / 2
    )
  }

  private var aspect: CGFloat {
    guard scene.width > 0, scene.height > 0 else { return 1 }
    return CGFloat(scene.width / scene.height)
  }

  private func draw(into context: CGContext, size: CGSize) {
    guard let placement = placement(in: size) else { return }

    context.saveGState()
    // Centred, then scaled. Scaling the context rather than the scene means stroke widths and dash
    // patterns scale with it for free, which is what a chart drawn at twice the size should do.
    //
    // `placement` is shared with the gesture handling above, so a touch is inverted through the same
    // numbers a mark was drawn with. Two copies of this arithmetic is how a tap ends up landing next to
    // the bar it looked like it hit.
    context.translateBy(x: placement.left, y: placement.top)
    context.scaleBy(x: CGFloat(placement.scale), y: CGFloat(placement.scale))

    // CoreText is lent to the renderer here. Without it a chart draws every mark and no label, which
    // is the renderer's deliberate answer to "shaping text is the platform's job".
    var target = CoreGraphicsTarget(context: context, drawText: CoreTextDrawing.draw)
    SceneWalk().draw(scene: scene, into: &target)

    context.restoreGState()
  }
}
