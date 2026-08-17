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

  var body: some View {
    Canvas { graphics, size in
      graphics.withCGContext { context in
        draw(into: context, size: size)
      }
    }
    // A scene has a declared size and an aspect ratio worth keeping; the canvas fills what it is
    // given and the drawing is centred inside it.
    .aspectRatio(aspect, contentMode: .fit)
  }

  private var aspect: CGFloat {
    guard scene.width > 0, scene.height > 0 else { return 1 }
    return CGFloat(scene.width / scene.height)
  }

  private func draw(into context: CGContext, size: CGSize) {
    guard scene.width > 0, scene.height > 0 else { return }
    let scale = min(size.width / CGFloat(scene.width), size.height / CGFloat(scene.height))

    context.saveGState()
    // Centred, then scaled. Scaling the context rather than the scene means stroke widths and dash
    // patterns scale with it for free, which is what a chart drawn at twice the size should do.
    context.translateBy(
      x: (size.width - CGFloat(scene.width) * scale) / 2,
      y: (size.height - CGFloat(scene.height) * scale) / 2
    )
    context.scaleBy(x: scale, y: scale)

    // CoreText is lent to the renderer here. Without it a chart draws every mark and no label, which
    // is the renderer's deliberate answer to "shaping text is the platform's job".
    var target = CoreGraphicsTarget(context: context, drawText: CoreTextDrawing.draw)
    SceneWalk().draw(scene: scene, into: &target)

    context.restoreGState()
  }
}
