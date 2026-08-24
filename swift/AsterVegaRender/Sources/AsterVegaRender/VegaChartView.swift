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
/// Which gestures a chart view installs.
///
/// The Compose renderer takes one callback per gesture and installs nothing for the ones a host leaves
/// null — `Modifier.chartPointerInput` returns its receiver untouched when they are all null — so a
/// host there pays for exactly what it asked for. This view takes a session rather than five closures,
/// and a session can answer every gesture, so the choice has to be stated separately. This is that
/// statement, and it exists for one case in particular: a chart inside a scroll view.
public struct ChartGestures: OptionSet, Sendable {
  public let rawValue: Int

  public init(rawValue: Int) {
    self.rawValue = rawValue
  }

  /// A tap, dispatched with the mark under it. Claims no drag; see ``ChartGestures/withoutDrag``.
  public static let tap = ChartGestures(rawValue: 1 << 0)
  /// A long press, which most specifications bind a tooltip to. **Claims the touch**: SwiftUI reports
  /// no location for one, so the place the finger landed has to come from a zero-distance drag.
  public static let longPress = ChartGestures(rawValue: 1 << 1)
  /// A drag, as a pan of the viewport. Claims the touch past ``VegaChartView``'s slop.
  public static let pan = ChartGestures(rawValue: 1 << 2)
  /// A pinch, as a zoom about the point the fingers were centred on.
  public static let zoom = ChartGestures(rawValue: 1 << 3)
  /// A pointer moving without touching — a trackpad or mouse on iPad. Claims nothing.
  public static let hover = ChartGestures(rawValue: 1 << 4)

  /// Everything, which is what a chart that owns its space wants.
  public static let all: ChartGestures = [.tap, .longPress, .pan, .zoom, .hover]

  /// Everything that does **not** claim a drag: a tap and a hover.
  ///
  /// For a chart in a scroll view, which is the case that made this necessary — a chart wider than its
  /// slot is usually put in a horizontal scroll view inside a page that scrolls vertically. Tooltips
  /// still work: a tap hovers as well, because a touch screen has no pointer and the engine treats one
  /// as both, so `"tooltip": true` answers a tap.
  public static let withoutDrag: ChartGestures = [.tap, .hover]

  /// Nothing at all, for a chart that is only being looked at. The same as passing no session.
  public static let none: ChartGestures = []
}

@available(macOS 14.0, iOS 17.0, tvOS 17.0, watchOS 10.0, *)
public struct VegaChartView: View {
  private let scene: AsterVega.Scene
  /// Where a touch goes. Nil for a chart that is only being looked at.
  private let session: ChartSession?
  /// Told where the chart ended up, for a host that has to invert a point itself.
  private let onPlaced: ((ChartPlacement) -> Void)?
  /// The language the accessibility tree's own summary sentence is written in.
  private let captions: VegaCaptions?
  /// What every font size is multiplied by when drawing; see ``ChartSession/textScale``.
  private let textScaleOverride: Double?
  private let resolveFontOverride: ((String) -> CTFont?)?
  /// How many data marks a reader may explore one by one; see the initialiser.
  private let accessibilityMaxExposedMarks: Int32
  /// Which gestures are installed; see the initialiser.
  private let gestures: ChartGestures
  /// Turns an `image` mark's URL into something drawable; see the initialiser.
  private let resolveImage: ((String) -> CGImage?)?
  /// Told the first time a URL cannot be resolved; see the initialiser.
  private let onUnresolvedImage: ((String) -> Void)?

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
  ///   - textScale: what every font size is multiplied by when drawing. Nil takes the session's, which
  ///     is what the layout was measured with — the two must agree or every label is painted at a
  ///     different size from the box reserved for it. Only a view drawing a scene it compiled itself,
  ///     with no session, needs to pass one.
  ///   - accessibilityMaxExposedMarks: how many **data marks** a reader may explore one by one before
  ///     a summary stands in for them. The engine's own `AccessibilityTree.MAX_EXPOSED_MARKS` by
  ///     default, which is a judgement rather than a fact — this app knows the size of the screen,
  ///     whether the chart is the page or a thumbnail on it, and what its own users have said. Only
  ///     marks count toward it and the axes and legend are exposed either way, so a chart of many
  ///     small points does not collapse on the strength of its axis labels.
  ///   - gestures: which gestures to install. ``ChartGestures/all`` by default. Pass
  ///     ``ChartGestures/withoutDrag`` for a chart inside a scroll view: a long press and a pan both
  ///     claim the touch, and with them installed neither the chart's scroll view nor the page around
  ///     it can move while a finger is on the drawing. A tap claims nothing and tooltips still work
  ///     through it, a tap being a hover as well on a screen with no pointer. Ignored where `session`
  ///     is nil, which installs nothing whatever this says.
  ///   - resolveImage: turns an `image` mark's URL into something drawable. Nil draws no URL images,
  ///     which is the default and is deliberate: a URL is not an image, and fetching one is a decision
  ///     about following an address the *specification* chose — the same argument `VegaDataLoader`
  ///     makes for data. `data:` URLs and engine-produced rasters need no resolver and are drawn
  ///     without one. `CoreGraphicsTarget` has taken one from the start; there was no way to reach it
  ///     through this view, so a chart with a remote image drew every other mark and a hole where the
  ///     image would be, with no supported way to supply a fetcher.
  ///   - onUnresolvedImage: told the first time an `image` mark's URL cannot be resolved, and not
  ///     again for that URL. An unresolved image leaves a hole in the chart and the draw carries on,
  ///     which is right — a chart is better with one mark missing than not drawn at all — and until
  ///     now the hole was all a host got, since `CoreGraphicsTarget` collects these into a target this
  ///     view builds per draw and discards with it.
  ///
  ///     **Called from the draw**, so treat it as a report rather than a place to set observable
  ///     state: log it, enqueue it, start a task. It fires once per URL because a refusal is cached
  ///     alongside the decodes — which is what makes it safe to have at all, and why
  ///     `CoreGraphicsTarget.clearImageCache()` exists for a host that has recovered.
  public init(
    scene: AsterVega.Scene,
    session: ChartSession? = nil,
    captions: VegaCaptions? = nil,
    textScale: Double? = nil,
    accessibilityMaxExposedMarks: Int32 = AccessibilityTree.shared.MAX_EXPOSED_MARKS,
    gestures: ChartGestures = .all,
    // **`onPlaced` comes before the other two closures on purpose.** Swift matches a trailing closure
    // by scanning *forward* from the last argument a caller labelled, and it takes the first parameter
    // that can accept one — so with `resolveImage` ahead of it,
    // `VegaChartView(scene:session:) { placement in … }` silently rebound to the image resolver and
    // failed with `cannot convert '()' to 'CGImage?'`. That idiom is the one this view's own
    // documentation and the demo both use, and it was broken by adding parameters that looked purely
    // additive. Anything closure-typed added here goes *after* this line.
    onPlaced: ((ChartPlacement) -> Void)? = nil,
    resolveImage: ((String) -> CGImage?)? = nil,
    onUnresolvedImage: ((String) -> Void)? = nil,
    resolveFont: ((String) -> CTFont?)? = nil
  ) {
    self.scene = scene
    self.session = session
    self.captions = captions
    self.textScaleOverride = textScale
    self.accessibilityMaxExposedMarks = accessibilityMaxExposedMarks
    self.gestures = gestures
    self.resolveImage = resolveImage
    self.onUnresolvedImage = onUnresolvedImage
    self.onPlaced = onPlaced
    self.resolveFontOverride = resolveFont
  }

  /// The factor the glyphs are drawn at: the session's, or one a caller stated.
  private var textScale: Double { textScaleOverride ?? session?.textScale ?? 1 }

  /// The face resolver the glyphs are drawn with: the session's, or one a caller stated.
  ///
  /// Defaulting to the session's is the important half. The layout was measured with *its* resolver,
  /// and a host that configured the session and then drew without passing the closure again would be
  /// painting faces the boxes were not measured for — every label off its baseline, from a seam that
  /// looked wired. So the default is the one that cannot be wrong, and stating it here overrides both
  /// measuring and drawing only when a caller means to.
  private var resolveFont: ((String) -> CTFont?)? { resolveFontOverride ?? session?.resolveFont }

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
    // **A tap, not a zero-distance drag.** `SpatialTapGesture` reports where it happened, which is the
    // whole reason a `DragGesture(minimumDistance: 0)` was standing in for it — and it does not claim
    // the drag on touch-down, which is what made an interactive chart inside a scroll view impossible.
    // A host that wants tooltips can now ask for `.tap` alone and get them: a tap hovers as well,
    // because a touch screen has no pointer and the engine treats one as both.
    .gesture(tap, including: mask(for: .tap))
    // Separate detectors, and a real minimum distance on this one. The tap above tolerates a small
    // movement and fails past it, so the two do not overlap and the hand-rolled slop is gone.
    .simultaneousGesture(pan, including: mask(for: .pan))
    .simultaneousGesture(pinch, including: mask(for: .zoom))
    .simultaneousGesture(longPress, including: mask(for: .longPress))
    // A pointer that moves without touching: a trackpad or mouse on iPad, and nothing on a phone. Wired
    // rather than dismissed as "iOS has no hover", because a chart whose tooltips work on one platform
    // only is a gap in the host rather than a property of the device.
    //
    // Gated in the handler rather than at attachment because a hover cannot claim a drag: it is the one
    // gesture here whose mere presence costs a host nothing.
    .onContinuousHover { phase in
      guard let session, gestures.contains(.hover) else { return }
      switch phase {
      case .active(let location):
        if let point = scenePoint(of: location) { session.hover(at: point) }
      case .ended:
        session.hover(at: nil)
      }
    }
    // **Nil session, nothing to touch**, which is what the `session` parameter has always documented.
    // The masks above already refuse every gesture, and this is the same statement one level up — it
    // is also the workaround an adopter had to apply from outside, and applying it here is what makes
    // the parameter's own documentation true. The VoiceOver overlay is unaffected: it is a sibling in
    // the `ZStack` with its own `allowsHitTesting(false)`, and activation goes through
    // `accessibilityAction`, which does not need hit testing.
    .allowsHitTesting(session != nil)
  }

  /// Whether a gesture is installed at all.
  ///
  /// `.none` is how SwiftUI disables a gesture, and the distinction it draws is the one this issue was
  /// about: every handler here already opened `guard let session else { return }`, so with no session
  /// the gestures did nothing — and a `DragGesture(minimumDistance: 0)` still **claims the drag on
  /// touch-down**. A chart wider than its slot is usually put in a horizontal scroll view inside a page
  /// that scrolls vertically, and neither scrolled: a swipe over the drawing moved nothing, and the
  /// page would not scroll while a finger was on the chart. The same host code on Compose scrolled
  /// both ways, because `Modifier.chartPointerInput` returns its receiver untouched when every callback
  /// is null.
  private func mask(for gesture: ChartGestures) -> GestureMask {
    session != nil && gestures.contains(gesture) ? .all : .none
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

  /// A tap, with the place it happened.
  ///
  /// `SpatialTapGesture` rather than the `DragGesture(minimumDistance: 0)` this used to be. The drag
  /// was standing in for a tap because `TapGesture` reports *that* a tap happened and not where, and
  /// the whole question is where — but a zero-distance drag claims the touch the moment a finger lands,
  /// so a host that only wanted tooltips had to accept that claim and a chart inside a scroll view
  /// became impossible. This reports the location and claims nothing until it has recognised a tap.
  private var tap: some Gesture {
    SpatialTapGesture(coordinateSpace: .local)
      .onEnded { value in
        guard let session, let point = scenePoint(of: value.location) else { return }
        session.tap(at: point)
      }
  }

  /// A pan, with a real minimum distance.
  ///
  /// The tap above tolerates a small movement and fails past it, so the two detectors do not overlap
  /// and the hand-rolled slop that used to tell them apart inside one gesture is gone. The Compose
  /// renderer has always been split this way — `detectTapGestures` and `detectTransformGestures` are
  /// separate `pointerInput` blocks, installed per callback — and this is that shape.
  private var pan: some Gesture {
    DragGesture(minimumDistance: Self.tapSlop)
      .onChanged { value in
        guard let session else { return }
        lastDown = value.startLocation
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
      .onEnded { _ in
        guard let session else { return }
        session.pan(by: Point(x: 0, y: 0), phase: GesturePhase.ended)
        panned = .zero
      }
  }

  /// A long press, and the one gesture here that still costs a drag claim.
  ///
  /// `LongPressGesture` reports no location, so the place the finger went down has to come from
  /// somewhere: a `DragGesture(minimumDistance: 0)` that records it and does nothing else. That claims
  /// the touch, which is why `.longPress` is not in ``ChartGestures/withoutDrag`` — a chart inside a
  /// scroll view asks for `.tap` and gets tooltips from it, a tap being a hover as well on a screen
  /// with no pointer.
  private var longPress: some Gesture {
    DragGesture(minimumDistance: 0)
      .onChanged { value in lastDown = value.startLocation }
      .simultaneously(
        with: LongPressGesture().onEnded { _ in
          guard let session, let point = scenePoint(of: lastDown) else { return }
          session.longPress(at: point)
        }
      )
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
  ///
  /// Through the **drawn** placement, pan and zoom included: a reader exploring by touch has to land on
  /// the mark where it is now. Android's `VegaAccessibilityHelper` maps its virtual nodes through the
  /// viewport for the same reason, and this side did not until the drawing did.
  private var accessibilityOverlay: some View {
    GeometryReader { proxy in
      let placement = drawnPlacement(in: proxy.size)
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
            // The **same** condition as the action below, not merely `activatable`. A trait is a
            // promise, and this one was being made with no session to keep it: VoiceOver announced
            // every mark as a button, a reader activated it, and nothing happened. The Compose
            // renderer gates the *role* rather than the action — `role = Role.Button` only where the
            // activation exists — and this is that.
            .accessibilityRespondsToUserInteraction(activatable(entry.element))
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
              guard let session, activatable(entry.element) else { return }
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
  func traits(for element: AccessibleElement) -> AccessibilityTraits {
    var traits = AccessibilityTraits()
    if activatable(element) { _ = traits.insert(.isButton) }
    if element.selected { _ = traits.insert(.isSelected) }
    if element.isSummary { _ = traits.insert(.isSummaryElement) }
    return traits
  }

  /// Whether activating this element actually does something.
  ///
  /// Three conditions, and the doc comment above `traits(for:)` already named two of them: the engine
  /// has to call the element a mark, and the mark has to carry a node id. The third is that there is a
  /// **session** to dispatch into, which the accessibility action has always required and the trait
  /// did not — so a chart drawn with no session announced every mark as a button and did nothing when
  /// one was activated. One function now, read by the trait, by
  /// `accessibilityRespondsToUserInteraction` and by the action, so the three cannot disagree again.
  /// Internal rather than private so the package's own tests can assert it: a SwiftUI view hierarchy
  /// cannot be inspected from `swift test`, and this predicate is the whole of the promise.
  func activatable(_ element: AccessibleElement) -> Bool {
    session != nil && element.activatable && element.nodeId != nil
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
        // Back into the engine's own form. The session now hands out numbers, which is what a host
        // wants; the tree wants the ids it issued.
        selectedNodeIds: ForeignNodeId.shared.setOf(
          values: (session?.selectedNodeIds ?? []).map { KotlinLong(value: $0) }),
        // The one sentence the tree writes itself — the dense-chart summary. English here because a
        // view has no locale of its own to consult; a host drawing charts in another language passes its
        // own captions to the compiler, and everything else in the tree is already in that language.
        captions: captions ?? VegaCaptionsCompanion.shared.English,
        maxExposedMarks: accessibilityMaxExposedMarks
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
  /// The **fit**: where the chart sits in the view before a reader has moved it.
  ///
  /// This is what a touch is inverted through and what is handed to `onPlaced`, and it deliberately
  /// carries no pan or zoom: `VegaChartController` subtracts its own `viewportOffset` and divides by its
  /// own `viewportScale`, so removing either here would remove it twice and a tap would drift further
  /// from the finger the further the chart had been panned.
  private func placement(in size: CGSize) -> (scale: Double, left: CGFloat, top: CGFloat)? {
    guard scene.width > 0, scene.height > 0, size.width > 0, size.height > 0 else { return nil }
    let scale = min(size.width / CGFloat(scene.width), size.height / CGFloat(scene.height))
    return (
      scale: Double(scale),
      left: (size.width - CGFloat(scene.width) * scale) / 2,
      top: (size.height - CGFloat(scene.height) * scale) / 2
    )
  }

  /// The fit **with the reader's pan and zoom on it**: what the pixels actually go through.
  ///
  /// Composed in the order the controller documents — translate by the offset, then scale by
  /// `contentScale * viewportScale` — and used by the drawing *and* by the accessibility frames, so a
  /// reader exploring by touch lands on the mark where it is now rather than where it started.
  private func drawnPlacement(in size: CGSize) -> (scale: Double, left: CGFloat, top: CGFloat)? {
    guard let fit = placement(in: size) else { return nil }
    let viewport = session?.viewport ?? .identity
    return (
      scale: fit.scale * viewport.scale,
      left: fit.left + CGFloat(viewport.offsetX),
      top: fit.top + CGFloat(viewport.offsetY)
    )
  }

  private var aspect: CGFloat {
    guard scene.width > 0, scene.height > 0 else { return 1 }
    return CGFloat(scene.width / scene.height)
  }

  func draw(into context: CGContext, size: CGSize) {
    guard let placement = drawnPlacement(in: size) else { return }

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
    // The scale the *layout* was measured with, so the glyphs fill the boxes the engine reserved.
    let scale = textScale
    // Captured beside the scale, and for the same reason: both describe how the layout was measured,
    // and the drawing has to be told both or it paints something the boxes were not reserved for.
    let faces = resolveFont
    var target = CoreGraphicsTarget(
      context: context,
      drawText: { run, fill, ctx in
        CoreTextDrawing.draw(run, fill, ctx, textScale: scale, resolveFont: faces)
      },
      // The seam `CoreGraphicsTarget` has always had and this view never passed. Its decode cache is
      // static, so a resolver is asked once per URL for the process rather than once per frame.
      resolveImage: resolveImage,
      // Handed to the target rather than read back off it: `unresolved` is what *this draw* met, so
      // reporting from it would report on every frame. The target fires this when its cache learns a
      // refusal, which is once per URL.
      onUnresolvedImage: onUnresolvedImage
    )
    SceneWalk().draw(scene: scene, into: &target)

    context.restoreGState()
  }
}

/// A tooltip to show, and where to put it.
///
/// Rows as well as text, because a design system will want its own layout — a two-column bubble, a
/// header and a value, a card — and the engine has no business choosing one. The `anchor` is in the
/// chart view's own coordinate space, so positioning is `.position(x:y:)` and nothing else.
public struct ChartTooltip: Sendable, Equatable {

  /// One line: what the field is called, and what it says. An unlabelled row is a bare value.
  public struct Row: Sendable, Equatable {
    public let label: String
    public let value: String

    public init(label: String, value: String) {
      self.label = label
      self.value = value
    }
  }

  public let rows: [Row]
  /// Every row as one string, `label: value` a line at a time — for a host with no opinion.
  public let text: String
  /// Where the touch was, in this view's pixels. Nil for a tooltip that arrived without one.
  public let anchor: CGPoint?

  public init(rows: [Row], text: String, anchor: CGPoint?) {
    self.rows = rows
    self.text = text
    self.anchor = anchor
  }
}

/// The pan and zoom a chart is drawn through, as a controller accumulated them.
///
/// The offset is in **pixels** and the scale is a factor, which is exactly how
/// `InteractionState.viewportOffset` and `viewportScale` hold them — so this crosses no conversion on
/// its way from the engine to the drawing. It is a separate type from ``ChartPlacement`` because the two
/// are composed and not interchangeable: the placement is where the chart *sits* in a view, and this is
/// what a reader has done to it since.
public struct ChartViewport: Sendable, Equatable {
  public let offsetX: Double
  public let offsetY: Double
  public let scale: Double

  public init(offsetX: Double, offsetY: Double, scale: Double) {
    self.offsetX = offsetX
    self.offsetY = offsetY
    self.scale = scale > 0 ? scale : 1
  }

  /// The identity: a chart nobody has moved.
  public static let identity = ChartViewport(offsetX: 0, offsetY: 0, scale: 1)
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
