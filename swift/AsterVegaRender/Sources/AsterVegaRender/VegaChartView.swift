import AsterVega
import CoreGraphics
import SwiftUI

/// A compiled scene as a SwiftUI view: drawn, touched and spoken.
///
/// The whole of the drawing is `SceneWalk` into a `CoreGraphicsTarget` — the same two types the
/// package's tests exercise, so what appears here is what those tests assert about. Around it are the
/// three things a host would otherwise write again: the aspect-fit arithmetic shared between drawing
/// and hit testing, the gesture vocabulary, and the VoiceOver overlay.
///
/// It lived in the demo, and an adopter counted the cost of that precisely: about 2,400 lines of this
/// renderer owned by hand, including the pieces that took real bug fixes to get right. So it is here,
/// public, and the demo uses it the way any other app does.
///
/// ```swift
/// @State private var session = ChartSession()
/// // …
/// if let scene = session.scene {
///   VegaChartView(scene: scene, session: session)
/// }
/// ```
///
/// **No flip.** SwiftUI's canvas already has its origin at the top left with y growing down, which is
/// the space a scene is in, so a chart drawn straight into it is the right way up. The bitmap tests in
/// the package *do* flip, because a `CGBitmapContext` on its own has its origin at the bottom left —
/// that difference belongs to the caller, which is why the renderer does not flip anything itself.
@available(macOS 14.0, iOS 17.0, tvOS 17.0, watchOS 10.0, *)
public struct VegaChartView: View {
  private let scene: AsterVega.Scene
  /// Where a touch goes. Nil for a chart that is only being looked at.
  private let session: ChartSession?
  /// Told where the chart ended up, for a host that has to invert a point itself.
  private let onPlaced: ((ChartPlacement) -> Void)?
  /// The language the accessibility tree's own summary sentence is written in.
  private let captions: VegaCaptions?

  /// Creates a chart view.
  ///
  /// - Parameters:
  ///   - scene: the compiled scene to draw. A `ChartSession` publishes one.
  ///   - session: where touches go. Nil draws a chart nobody can touch, which is right for one that is
  ///     only being looked at — the drawing and the VoiceOver overlay still work.
  ///   - onPlaced: called with the fit scale and centring offset whenever the view is laid out. Only a
  ///     host that has to turn a point of its own into scene coordinates needs it; the gestures and the
  ///     accessibility overlay use the same numbers internally.
  ///   - captions: the language the accessibility tree's dense-chart summary is written in. Every other
  ///     label reaches the scene already in the chart's own language, from the compiler's locale.
  public init(
    scene: AsterVega.Scene,
    session: ChartSession? = nil,
    captions: VegaCaptions? = nil,
    onPlaced: ((ChartPlacement) -> Void)? = nil
  ) {
    self.scene = scene
    self.session = session
    self.captions = captions
    self.onPlaced = onPlaced
  }

  /// The canvas's size, remembered so a gesture can undo the same placement the drawing used.
  @State private var canvasSize: CGSize = .zero
  /// The pan and pinch reported so far, so each gesture change can be sent as an increment.
  @State private var panned: CGSize = .zero
  @State private var pinched: CGFloat = 1
  /// Where the last finger went down, for a long press — which SwiftUI reports without a location.
  @State private var lastDown: CGPoint = .zero

  public var body: some View {
    ZStack {
      canvas
      // VoiceOver, as real positioned views rather than `accessibilityChildren`.
      //
      // That distinction is the whole of this: `accessibilityChildren` produces elements with labels and a
      // frame of `(inf, inf, 0, 0)`, so a reader can swipe through a chart but cannot *touch* it — and
      // touch exploration is most of what makes a chart explorable at all. Android's
      // `ExploreByTouchHelper` gives every virtual node a rectangle; this is the same thing in SwiftUI.
      //
      // Hit testing is off, so the overlay cannot intercept a finger. VoiceOver activation goes through
      // the accessibility action, which does not need it.
      accessibilityOverlay
        .allowsHitTesting(false)
    }
  }

  private var canvas: some View {
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
    .simultaneousGesture(pinch)
    .simultaneousGesture(
      LongPressGesture().onEnded { _ in
        // A long press has no location in SwiftUI, so the last place a finger went down is the honest
        // answer — which the drag gesture above has already recorded.
        guard let session, let point = scenePoint(of: lastDown) else { return }
        session.longPress(at: point)
      }
    )
    // A pointer that moves without touching: a trackpad or mouse on iPad, and nothing on a phone. Wired
    // rather than dismissed as "iOS has no hover", because a chart whose tooltips work on one platform
    // only is a gap in the host rather than a property of the device.
    .onContinuousHover { phase in
      guard let session else { return }
      switch phase {
      case .active(let location):
        if let point = scenePoint(of: location) { session.hover(at: point) }
      case .ended:
        session.hover(at: nil)
      }
    }
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
    onPlaced?(ChartPlacement(scale: placement.scale, left: placement.left, top: placement.top))
  }

  /// The whole gesture vocabulary, so a chart answers the same touches on iOS as it does on Android.
  ///
  /// `DragGesture(minimumDistance: 0)` rather than `TapGesture`, because a tap gesture reports *that* a
  /// tap happened and not where — and the whole question is where. A drag that stays put is a tap; one
  /// that moves is a pan, which is the same distinction the Android view's `GestureDetector` makes.
  private var touch: some Gesture {
    DragGesture(minimumDistance: 0)
      .onChanged { value in
        guard let session else { return }
        lastDown = value.startLocation
        let travelled = hypot(value.translation.width, value.translation.height)
        guard travelled > Self.tapSlop else { return }
        // Incremental, because the controller adds each delta to the viewport offset: handing it the
        // gesture's cumulative translation every time would accelerate the pan quadratically.
        let previous = panned
        panned = value.translation
        session.pan(
          by: Point(
            x: Double(value.translation.width - previous.width) / max(scale, 0.0001),
            y: Double(value.translation.height - previous.height) / max(scale, 0.0001)
          ),
          phase: GesturePhase.changed
        )
      }
      .onEnded { value in
        guard let session else { return }
        let travelled = hypot(value.translation.width, value.translation.height)
        if travelled <= Self.tapSlop {
          if let point = scenePoint(of: value.location) { session.tap(at: point) }
        } else {
          session.pan(by: Point(x: 0, y: 0), phase: GesturePhase.ended)
        }
        panned = .zero
      }
  }

  /// A pinch. The anchor is where the fingers were centred, so a chart zooms about what is being looked
  /// at rather than about its own middle.
  private var pinch: some Gesture {
    MagnifyGesture()
      .onChanged { value in
        guard let session else { return }
        // Also incremental: `magnification` is cumulative and the controller multiplies.
        let step = value.magnification / max(pinched, 0.0001)
        pinched = value.magnification
        session.zoom(
          by: Double(step),
          at: scenePoint(of: CGPoint(x: value.startLocation.x, y: value.startLocation.y))
            ?? Point(x: 0, y: 0),
          phase: GesturePhase.changed
        )
      }
      .onEnded { _ in
        pinched = 1
        session?.zoom(by: 1, at: Point(x: 0, y: 0), phase: GesturePhase.ended)
      }
  }

  /// The accessibility elements, positioned over the chart they describe.
  private var accessibilityOverlay: some View {
    GeometryReader { proxy in
      let placement = placement(in: proxy.size)
      ZStack(alignment: .topLeading) {
        ForEach(accessibleElements, id: \.offset) { entry in
          let scale = placement?.scale ?? 1
          Rectangle()
            .fill(Color.clear)
            // At least a point in each direction: a rule or a zero-height mark would otherwise have a
            // frame a reader cannot land on.
            .frame(
              width: max(entry.element.bounds.width * scale, 1),
              height: max(entry.element.bounds.height * scale, 1)
            )
            .offset(
              x: entry.element.bounds.left * scale + Double(placement?.left ?? 0),
              y: entry.element.bounds.top * scale + Double(placement?.top ?? 0)
            )
            .accessibilityElement()
            .accessibilityLabel(entry.element.label)
            // What kind of thing it is, in the chart's own language: the engine writes this through
            // its locale, so a Dutch chart says "lijn-markering" rather than "line mark".
            .accessibilityRespondsToUserInteraction(entry.element.activatable)
            .accessibilityInputLabels(
              entry.element.roleDescription.map { [entry.element.label, $0] } ?? [entry.element.label]
            )
            // A **button only where activating it does something.** Every element used to be a button,
            // which tells a reader they can activate an axis caption and then does nothing when they
            // try; `activatable` is the engine's own answer to which elements are marks.
            .accessibilityAddTraits(traits(for: entry.element))
            // `.default` rather than an unnamed action: without the kind this registers a *custom* action,
            // which a reader has to go looking for and which an activation does not invoke.
            .accessibilityAction(.default) {
              // Scaled, because `tap` takes surface coordinates and the controller divides by
              // `contentScale` to reach the scene. Handing it the element's scene-space centre applied the
              // fit factor twice — the same trap `contentScale` has set twice before on this project.
              guard let session, entry.element.nodeId != nil else { return }
              session.tap(
                at: Point(
                  x: (entry.element.bounds.left + entry.element.bounds.width / 2) * scale,
                  y: (entry.element.bounds.top + entry.element.bounds.height / 2) * scale
                )
              )
            }
        }
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
  }

  /// The traits an element carries.
  ///
  /// A mark is a button because activating it selects it and may open a tooltip; a guide's caption is
  /// text, because there is nothing behind it to activate. Both hosts used to say button for
  /// everything, which is a promise the chart does not keep.
  private func traits(for element: AccessibleElement) -> AccessibilityTraits {
    var traits = AccessibilityTraits()
    if element.activatable { _ = traits.insert(.isButton) }
    if element.selected { _ = traits.insert(.isSelected) }
    if element.isSummary { _ = traits.insert(.isSummaryElement) }
    return traits
  }

  /// The chart's accessible elements, paired with an index so `ForEach` has something stable to key on.
  ///
  /// `AccessibleElement` comes from Kotlin and is not `Identifiable`; the offset is enough here because
  /// the list is rebuilt whenever the scene is.
  private var accessibleElements: [(offset: Int, element: AccessibleElement)] {
    guard let scene = session?.scene ?? sceneIfDescribable else { return [] }
    return Array(
      AccessibilityTree.shared.elements(
        scene: scene,
        selectedNodeIds: session?.selectedNodeIds ?? [],
        // The one sentence the tree writes itself — the dense-chart summary. English here because a
        // view has no locale of its own to consult; a host drawing charts in another language passes its
        // own captions to the compiler, and everything else in the tree is already in that language.
        captions: captions ?? VegaCaptionsCompanion.shared.English
      )
      .enumerated()
    ).map { (offset: $0.offset, element: $0.element) }
  }

  /// The scene this canvas draws, for the case where there is no session to ask.
  private var sceneIfDescribable: AsterVega.Scene? { scene }

  /// The fit scale, for turning a gesture's screen distance into scene distance.
  private var scale: CGFloat {
    CGFloat(placement(in: canvasSize)?.scale ?? 1)
  }

  /// How far a finger may travel and still be a tap rather than a pan.
  private static let tapSlop: CGFloat = 8

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

/// Where a chart ended up inside the view: the fit scale, and the offset it was centred by.
///
/// Handed to a host through ``VegaChartView/init(scene:session:onPlaced:)`` for the one thing a host
/// might have to do itself — turn a point of its own into the chart's coordinates. The gestures and the
/// accessibility overlay share these numbers internally, which is the point: two copies of this
/// arithmetic is how a tap lands beside the bar it looked like it hit.
public struct ChartPlacement: Sendable, Equatable {
  /// Scene units to points. A scene is drawn scaled to fit and centred.
  public let scale: Double
  /// How far the drawing was inset from the view's left edge, in points.
  public let left: CGFloat
  /// And from its top edge.
  public let top: CGFloat

  public init(scale: Double, left: CGFloat, top: CGFloat) {
    self.scale = scale
    self.left = left
    self.top = top
  }

  /// A point in the view turned into the chart's own surface coordinates.
  ///
  /// The inverse of what the drawing does, minus the division by the scale: `contentScale` is part of
  /// the controller's contract and it divides by that itself, so what a host hands over is a point in
  /// scaled surface space with the offset removed. Applying the fit twice is the trap this method
  /// exists to keep a host out of.
  public func scenePoint(of location: CGPoint) -> Point {
    Point(x: Double(location.x - left), y: Double(location.y - top))
  }
}
