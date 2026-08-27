#if canImport(CoreGraphics)
import AsterVega
import CoreGraphics
import Foundation
import ImageIO

/// Draws into a `CGContext` — the renderer a device actually uses.
///
/// Every coordinate arriving here is already in surface space, because ``SceneWalk`` composes the
/// transforms itself. So this type holds no matrix stack: the only state it keeps is the graphics
/// state it pushes for a group's clip and opacity, which is also the only state it has to unwind.
public struct CoreGraphicsTarget: DrawTarget {

  private let context: CGContext
  /// The text drawing is handed out, because measuring and shaping text is the one thing a scene
  /// cannot fully precompute for a foreign surface: the engine positions a run, and the platform
  /// draws the glyphs. A caller that has CoreText wires it in; one that does not gets no text rather
  /// than wrong text.
  private let drawText: ((DrawTextRun, Brush?, CGContext) -> Void)?

  /// Resolves an image URL to something drawable. Nil draws no images, which is the default.
  ///
  /// A URL is not an image and fetching one is not a renderer's job: a chart is often data a reader
  /// pasted, so the address in it is the specification's choice and the policy about following it belongs
  /// to the host — the same argument `DataLoader` makes for data. `data:` URLs and engine-produced
  /// rasters need no resolver and are handled without one.
  private let resolver: ((String) -> CGImage?)?

  /// URLs the resolver could not answer, for a caller that wants to say so.
  ///
  /// A hole in a chart that nobody mentions looks like a specification that asked for nothing, which is
  /// the opposite of this project's discipline about silence.
  public private(set) var unresolved: [String] = []

  /// Told the first time a URL cannot be resolved, and not again for that URL.
  ///
  /// Not read off ``unresolved``, and the difference is the point: that list is what *this draw* could
  /// not resolve, so a caller reporting from it would report on every frame. A refusal is cached, so
  /// this fires when the cache learns something — once per URL until `clearImageCache()`.
  private let onUnresolvedImage: ((String) -> Void)?

  public init(
    context: CGContext,
    drawText: ((DrawTextRun, Brush?, CGContext) -> Void)? = nil,
    resolveImage: ((String) -> CGImage?)? = nil,
    onUnresolvedImage: ((String) -> Void)? = nil
  ) {
    self.context = context
    self.drawText = drawText
    self.resolver = resolveImage
    self.onUnresolvedImage = onUnresolvedImage
  }

  public mutating func beginGroup(clip: Rect?) {
    context.saveGState()
    if let clip { context.clip(to: cg(clip)) }
    // No `setAlpha`: opacity arrives in each paint's alpha, and a group-wide alpha here would apply
    // it a second time to every child.
  }

  public mutating func endGroup() {
    context.restoreGState()
  }

  public mutating func rect(
    _ rect: Rect, corners: Corners, fill: Brush?, stroke: StrokePaint?, blend: SceneBlendMode
  ) {
    let path = corners.isSquare ? CGPath(rect: cg(rect), transform: nil) : rounded(rect, corners)
    paint(path: path, fill: fill, stroke: stroke, blend: blend)
  }

  public mutating func line(from: Point, to: Point, stroke: StrokePaint?, blend: SceneBlendMode) {
    guard let stroke else { return }
    let path = CGMutablePath()
    path.move(to: cg(from))
    path.addLine(to: cg(to))
    paint(path: path, fill: nil, stroke: stroke, blend: blend)
  }

  public mutating func path(
    _ commands: [PathCommand], fill: Brush?, stroke: StrokePaint?, blend: SceneBlendMode
  ) {
    let path = CGMutablePath()
    for command in commands {
      switch command {
      case .move(let to): path.move(to: cg(to))
      case .line(let to): path.addLine(to: cg(to))
      case .cubic(let one, let two, let to):
        path.addCurve(to: cg(to), control1: cg(one), control2: cg(two))
      case .close: path.closeSubpath()
      }
    }
    paint(path: path, fill: fill, stroke: stroke, blend: blend)
  }

  public mutating func text(
    _ run: DrawTextRun, fill: Brush?, stroke: StrokePaint?, blend: SceneBlendMode
  ) {
    // Bracketed rather than passed down, because the text drawing is a closure the host supplies
    // and a blend mode is graphics state: setting it here applies to whatever that closure draws.
    guard blend != SceneBlendMode.normal else {
      drawText?(run, fill, context)
      return
    }
    context.saveGState()
    context.setBlendMode(Self.cgBlend(blend))
    drawText?(run, fill, context)
    context.restoreGState()
  }

  public mutating func image(
    url: String,
    raster: DrawRaster?,
    in rect: Rect,
    fit: DrawImageFit,
    smooth: Bool,
    opacity: Double,
    blend: SceneBlendMode
  ) {
    guard let decoded = resolve(url: url, raster: raster) else {
      // Said rather than swallowed: an image that could not be resolved leaves a hole in the chart, and
      // a hole nobody mentions looks like a specification that asked for nothing. The engine reports its
      // own unresolved images as diagnostics; a renderer's resolver failures belong to the renderer, so
      // they are collected here for the caller to read.
      if !url.isEmpty { unresolved.append(url) }
      return
    }

    let box = fit == .contain ? contained(decoded, in: rect) : cg(rect)
    context.saveGState()
    context.setBlendMode(Self.cgBlend(blend))
    context.setAlpha(CGFloat(opacity))
    context.interpolationQuality = smooth ? .high : .none
    // Flipped about the destination: a CGImage is drawn bottom-up, and every coordinate here is in a
    // space whose y grows down — the same asymmetry CoreText has, and the same fix.
    context.translateBy(x: 0, y: box.midY * 2)
    context.scaleBy(x: 1, y: -1)
    context.draw(decoded, in: box)
    context.restoreGState()
  }

  /// The image for a URL or a raster, decoded once and kept.
  private func resolve(url: String, raster: DrawRaster?) -> CGImage? {
    if let raster {
      if let cached = Self.cache.image(forRaster: raster.digest) { return cached }
      // The engine's own PNG encoder, which the SVG renderer already uses for data URLs. One call across
      // the boundary instead of a call per pixel: a `KotlinIntArray` is read element by element from
      // Swift, and a modest heatmap is 120,000 of those.
      let decoded = Self.decode(dataURL: PngEncoder.shared.dataUrl(image: raster.image))
      if let decoded { Self.cache.store(decoded, forRaster: raster.digest) }
      return decoded
    }
    guard !url.isEmpty else { return nil }
    switch Self.cache.answer(forURL: url) {
    case .image(let cached): return cached
    // **The failure is cached too**, and that is not only tidiness. Nothing remembered a URL that
    // could not be resolved, so a host's fetcher was called again on every frame for an address that
    // had already said no — and any report of it would have fired once per frame with it. Once per
    // URL is both the cheaper answer and the one a host can act on. `clearImageCache()` is how a
    // transient failure gets a second chance.
    case .unresolvable: return nil
    case .unknown: break
    }
    // A `data:` URL needs no host at all, so it is answered here rather than pushed onto a resolver that
    // would have to know how. Anything else is the host's business.
    let decoded = url.hasPrefix("data:") ? Self.decode(dataURL: url) : resolver?(url)
    if let decoded {
      Self.cache.store(decoded, forURL: url)
    } else if Self.cache.storeUnresolvable(url) {
      // Only where the cache **learned** it. `unresolved` below still records every hole this draw
      // met, which is what a caller inspecting one draw wants; a report to a host has to be once per
      // URL or it is once per frame.
      onUnresolvedImage?(url)
    }
    return decoded
  }

  /// Forgets every decoded image, and every URL that could not be decoded.
  ///
  /// The second half is why this is public. Failures are remembered so a resolver is asked once per
  /// URL rather than once per frame, which means a fetch that failed because the network was down
  /// stays failed — so a host that recovers has to be able to say so.
  public static func clearImageCache() {
    cache.clear()
  }

  /// Fits an image inside `rect`, centred, preserving its aspect ratio.
  private func contained(_ image: CGImage, in rect: Rect) -> CGRect {
    let width = Double(image.width)
    let height = Double(image.height)
    guard width > 0, height > 0 else { return cg(rect) }
    let scale = min(rect.width / width, rect.height / height)
    let drawn = CGSize(width: width * scale, height: height * scale)
    return CGRect(
      x: rect.x + (rect.width - drawn.width) / 2,
      y: rect.y + (rect.height - drawn.height) / 2,
      width: drawn.width,
      height: drawn.height
    )
  }

  private static func decode(dataURL: String) -> CGImage? {
    guard let comma = dataURL.firstIndex(of: ","),
      let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...])),
      let source = CGImageSourceCreateWithData(data as CFData, nil)
    else { return nil }
    return CGImageSourceCreateImageAtIndex(source, 0, nil)
  }

  // MARK: - Painting

  /// CSS `mix-blend-mode` as CoreGraphics's own, which has all sixteen and then some.
  ///
  /// The mapping is one-to-one and named identically on both sides, which is not a coincidence:
  /// `CGBlendMode`'s separable and non-separable modes are the PDF blend modes, and CSS took its
  /// list from the same place.
  static func cgBlend(_ blend: SceneBlendMode) -> CGBlendMode {
    switch blend {
    case SceneBlendMode.multiply: return .multiply
    case SceneBlendMode.screen: return .screen
    case SceneBlendMode.overlay: return .overlay
    case SceneBlendMode.darken: return .darken
    case SceneBlendMode.lighten: return .lighten
    case SceneBlendMode.colorDodge: return .colorDodge
    case SceneBlendMode.colorBurn: return .colorBurn
    case SceneBlendMode.hardLight: return .hardLight
    case SceneBlendMode.softLight: return .softLight
    case SceneBlendMode.difference: return .difference
    case SceneBlendMode.exclusion: return .exclusion
    case SceneBlendMode.hue: return .hue
    case SceneBlendMode.saturation: return .saturation
    case SceneBlendMode.color: return .color
    case SceneBlendMode.luminosity: return .luminosity
    default: return .normal
    }
  }

  private func paint(path: CGPath, fill: Brush?, stroke: StrokePaint?, blend: SceneBlendMode) {
    // Bracketed around the whole item, fill and stroke together, because that is what a blend mode
    // means: the mark composites against what is under it, once.
    let blended = blend != SceneBlendMode.normal
    if blended {
      context.saveGState()
      context.setBlendMode(Self.cgBlend(blend))
    }
    defer { if blended { context.restoreGState() } }
    if let fill {
      switch fill {
      case .solid(let colour):
        context.setFillColor(cg(colour))
        context.addPath(path)
        context.fillPath()
      case .linear, .radial:
        // CoreGraphics has no gradient *fill*: a gradient is drawn over a region, so the region is
        // the clip. Saved and restored around it, because a clip is the one piece of state here that
        // would otherwise leak into the next mark.
        context.saveGState()
        context.addPath(path)
        context.clip()
        draw(gradient: fill)
        context.restoreGState()
      }
    }
    guard let stroke else { return }
    // A gradient-stroked mark is drawn by clipping to the *stroked* outline and filling that, which
    // is the same trick: `replacePathWithStrokedPath` turns the pen into a region.
    if case .solid(let colour) = stroke.brush {
      context.setStrokeColor(cg(colour))
    } else {
      context.saveGState()
      context.addPath(path)
      applyStrokeStyle(stroke)
      context.replacePathWithStrokedPath()
      context.clip()
      draw(gradient: stroke.brush)
      context.restoreGState()
      return
    }
    context.setLineWidth(CGFloat(stroke.width))
    context.setLineCap(
      stroke.cap == .round ? .round : stroke.cap == .square ? .square : .butt
    )
    context.setLineJoin(
      stroke.join == .round ? .round : stroke.join == .bevel ? .bevel : .miter
    )
    context.setMiterLimit(CGFloat(stroke.miterLimit))
    if stroke.dash.isEmpty {
      context.setLineDash(phase: 0, lengths: [])
    } else {
      context.setLineDash(
        phase: CGFloat(stroke.dashOffset), lengths: stroke.dash.map { CGFloat($0) }
      )
    }
    context.addPath(path)
    context.strokePath()
  }

  /// Sets the pen up: width, caps, joins and dashes, without touching colour.
  private func applyStrokeStyle(_ stroke: StrokePaint) {
    context.setLineWidth(CGFloat(stroke.width))
    context.setLineCap(stroke.cap == .round ? .round : stroke.cap == .square ? .square : .butt)
    context.setLineJoin(stroke.join == .round ? .round : stroke.join == .bevel ? .bevel : .miter)
    context.setMiterLimit(CGFloat(stroke.miterLimit))
    if stroke.dash.isEmpty {
      context.setLineDash(phase: 0, lengths: [])
    } else {
      context.setLineDash(
        phase: CGFloat(stroke.dashOffset), lengths: stroke.dash.map { CGFloat($0) }
      )
    }
  }

  /// Draws a gradient across the current clip.
  ///
  /// `drawsBeforeStartLocation`/`drawsAfterEndLocation` are what make this match every other
  /// renderer: a gradient's stops describe the span between its two points, and the area outside that
  /// span takes the nearest stop's colour rather than nothing. Without them a bar whose gradient runs
  /// between two interior points would have transparent ends.
  private func draw(gradient brush: Brush) {
    let alpha = brush.alpha
    switch brush {
    case .solid:
      return
    case .linear(let from, let to, let stops, _):
      guard let gradient = cg(stops: stops, alpha: alpha) else { return }
      context.drawLinearGradient(
        gradient,
        start: cg(from),
        end: cg(to),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
      )
    case .radial(let centre, let radius, let stops, _):
      guard let gradient = cg(stops: stops, alpha: alpha) else { return }
      context.drawRadialGradient(
        gradient,
        startCenter: cg(centre),
        startRadius: 0,
        endCenter: cg(centre),
        endRadius: CGFloat(radius),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
      )
    }
  }

  private func cg(stops: [GradientStop], alpha: Double) -> CGGradient? {
    let colours = stops.map { stop -> CGColor in
      // The item's opacity is multiplied into each stop rather than set as a context alpha, because a
      // gradient is drawn inside a saved state whose alpha would also apply to the clip's edges.
      cg(
        Paint(
          red: stop.paint.red, green: stop.paint.green, blue: stop.paint.blue,
          alpha: stop.paint.alpha * alpha
        )
      )
    }
    return CGGradient(
      colorsSpace: Self.sRGB,
      colors: colours as CFArray,
      locations: stops.map { CGFloat($0.offset) }
    )
  }

  /// A rectangle with four independent radii, each clamped so opposite corners cannot overlap.
  private func rounded(_ rect: Rect, _ corners: Corners) -> CGPath {
    let limit = min(rect.width, rect.height) / 2
    let tl = min(corners.topLeft, limit), tr = min(corners.topRight, limit)
    let br = min(corners.bottomRight, limit), bl = min(corners.bottomLeft, limit)
    let box = cg(rect)
    let path = CGMutablePath()
    path.move(to: CGPoint(x: box.minX + tl, y: box.minY))
    path.addLine(to: CGPoint(x: box.maxX - tr, y: box.minY))
    path.addArc(tangent1End: CGPoint(x: box.maxX, y: box.minY),
                tangent2End: CGPoint(x: box.maxX, y: box.minY + tr), radius: tr)
    path.addLine(to: CGPoint(x: box.maxX, y: box.maxY - br))
    path.addArc(tangent1End: CGPoint(x: box.maxX, y: box.maxY),
                tangent2End: CGPoint(x: box.maxX - br, y: box.maxY), radius: br)
    path.addLine(to: CGPoint(x: box.minX + bl, y: box.maxY))
    path.addArc(tangent1End: CGPoint(x: box.minX, y: box.maxY),
                tangent2End: CGPoint(x: box.minX, y: box.maxY - bl), radius: bl)
    path.addLine(to: CGPoint(x: box.minX, y: box.minY + tl))
    path.addArc(tangent1End: CGPoint(x: box.minX, y: box.minY),
                tangent2End: CGPoint(x: box.minX + tl, y: box.minY), radius: tl)
    path.closeSubpath()
    return path
  }

  private func cg(_ point: Point) -> CGPoint { CGPoint(x: point.x, y: point.y) }

  private func cg(_ rect: Rect) -> CGRect {
    CGRect(x: rect.x, y: rect.y, width: rect.width, height: rect.height)
  }

  /// The colour space a scene's colours are actually in.
  ///
  /// A specification's colours are CSS colours, and CSS is sRGB. `CGColor(red:green:blue:alpha:)`
  /// does *not* mean that — it builds the colour in the generic RGB space, which the context then
  /// converts on the way in, and `steelblue` arrived on an sRGB surface as rgb(86,149,193) instead of
  /// rgb(70,130,180). Every colour was quietly wrong, by an amount too small to notice by eye and
  /// large enough to be a different colour; naming the space is the fix.
  private static let sRGB = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

  /// Decoded images, kept across frames.
  ///
  /// Static and locked because a target is created per frame while the images it draws are not: a
  /// heatmap re-encoded and re-decoded on every redraw would be the most expensive thing in the renderer.
  /// Keyed by a raster's digest, which is stable for identical pixels, or by the URL.
  private static let cache = ImageCache()

  /// What the cache knows about a URL: an image, a refusal, or nothing yet.
  ///
  /// Three cases rather than an optional, because "no image" was two different facts sharing one
  /// answer — never asked, and asked and refused — and telling them apart is what stops a resolver
  /// being called once per frame for an address that has already declined.
  private enum Answer {
    case image(CGImage)
    case unresolvable
    case unknown
  }

  /// **Bounded**, in bytes of decoded pixels rather than in entries.
  ///
  /// This is a `static` cache, so it lives for the life of the *process* — a host that shows a
  /// hundred charts, each with its own images, holds every one of them until it exits. And an entry
  /// count is the wrong bound: an engine-produced `heatmap` raster is megabytes where an icon is
  /// kilobytes, so ten entries can be ten kilobytes or a hundred megabytes. The bound is a byte
  /// budget, evicted least-recently-used, which is the shape the audit asked for and the reason it
  /// is not simply a smaller dictionary.
  ///
  /// `clearImageCache()` remains, for a host saying an address now holds something different.
  private final class ImageCache: @unchecked Sendable {
    /// About sixty-four megabytes of decoded pixels, which is several full-screen rasters and
    /// hundreds of icons.
    private static let byteBudget = 64 * 1024 * 1024

    private var byDigest: [Int64: CGImage] = [:]
    private var byURL: [String: CGImage] = [:]
    private var unresolvable: Set<String> = []
    /// Keys in least-recently-used order, oldest first, tagged by which dictionary holds them.
    private var order: [Key] = []
    private var bytes = 0
    private let lock = NSLock()

    private enum Key: Hashable {
      case digest(Int64)
      case url(String)
    }

    /// A decoded image's cost. `bytesPerRow * height` is what CoreGraphics actually holds.
    private func cost(_ image: CGImage) -> Int { image.bytesPerRow * image.height }

    /// Marks a key as just used, and evicts from the far end until the budget is met.
    ///
    /// Called with the lock already held.
    private func touch(_ key: Key, adding added: Int) {
      order.removeAll { $0 == key }
      order.append(key)
      bytes += added
      while bytes > Self.byteBudget, let oldest = order.first, order.count > 1 {
        order.removeFirst()
        switch oldest {
        case .digest(let digest):
          if let evicted = byDigest.removeValue(forKey: digest) { bytes -= cost(evicted) }
        case .url(let url):
          if let evicted = byURL.removeValue(forKey: url) { bytes -= cost(evicted) }
        }
      }
    }

    func answer(forURL url: String) -> Answer {
      lock.lock()
      defer { lock.unlock() }
      if let image = byURL[url] {
        touch(.url(url), adding: 0)
        return .image(image)
      }
      return unresolvable.contains(url) ? .unresolvable : .unknown
    }

    /// Records a refusal, answering whether it was news.
    func storeUnresolvable(_ url: String) -> Bool {
      lock.lock()
      defer { lock.unlock() }
      return unresolvable.insert(url).inserted
    }

    func clear() {
      lock.lock()
      defer { lock.unlock() }
      byDigest.removeAll()
      byURL.removeAll()
      unresolvable.removeAll()
      order.removeAll()
      bytes = 0
    }

    func image(forRaster digest: Int64) -> CGImage? {
      lock.lock()
      defer { lock.unlock() }
      guard let image = byDigest[digest] else { return nil }
      touch(.digest(digest), adding: 0)
      return image
    }

    func store(_ image: CGImage, forRaster digest: Int64) {
      lock.lock()
      defer { lock.unlock() }
      if let previous = byDigest[digest] { bytes -= cost(previous) }
      byDigest[digest] = image
      touch(.digest(digest), adding: cost(image))
    }

    func store(_ image: CGImage, forURL url: String) {
      lock.lock()
      defer { lock.unlock() }
      if let previous = byURL[url] { bytes -= cost(previous) }
      byURL[url] = image
      touch(.url(url), adding: cost(image))
    }
  }

  private func cg(_ paint: Paint) -> CGColor {
    CGColor(
      colorSpace: Self.sRGB,
      components: [
        CGFloat(paint.red), CGFloat(paint.green), CGFloat(paint.blue), CGFloat(paint.alpha),
      ]
    ) ?? CGColor(gray: 0, alpha: CGFloat(paint.alpha))
  }
}
#endif
