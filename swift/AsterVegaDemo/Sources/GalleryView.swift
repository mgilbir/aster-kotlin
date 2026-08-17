import AsterVega
import SwiftUI

/// The gallery: pick a specification, see the chart the engine compiled and what it could not honour.
///
/// Showing the diagnostics is the point, not decoration. This engine's discipline is that nothing is
/// silently ignored, and a screen that lists every part of a specification it dropped is the only place
/// that claim is visible to someone who is not reading the test suite.
struct GalleryView: View {
  @State private var charts: [BundledSpec] = []
  /// Filled in behind the list, one specification at a time, so nothing waits on a compile.
  @State private var statuses: [String: SpecStatus] = [:]
  @State private var path: [String] = []



  /// A chart to open on launch, from `-chart <name>`.
  ///
  /// `xcrun simctl launch … -chart bar` opens straight onto that chart, which is what makes a
  /// screenshot of a *drawing* scriptable — the simulator has no way to tap a row from the command
  /// line. It reads through `UserDefaults` because that is where a launch argument of this shape
  /// lands.
  private var requestedChart: String? {
    UserDefaults.standard.string(forKey: "chart")
  }

  /// The paste screen's slot in the navigation path. Not a chart name, and cannot collide with one:
  /// a bundled specification's id comes from a file name.
  private static let pasteDestination = "•paste"

  var body: some View {
    NavigationStack(path: $path) {
      // `List { ForEach }` rather than `List(charts)`, because the latter resolves to SwiftUI's
      // *editable* overload — it wants a `Binding` and the error it gives says nothing about that.
      List {
        Section {
          NavigationLink(value: Self.pasteDestination) {
            VStack(alignment: .leading, spacing: 2) {
              Text("Paste a specification").font(.body)
              Text("your own Vega JSON, with its controls")
                .font(.caption)
                .foregroundStyle(Color.secondary)
            }
          }
        }

        ForEach(charts) { chart in
          NavigationLink(value: chart.id) {
            VStack(alignment: .leading, spacing: 2) {
              Text(chart.name).font(.body)
              Text(subtitle(for: chart))
                .font(.caption)
                .foregroundStyle(statuses[chart.id]?.failure == nil ? Color.secondary : Color.red)
            }
          }
        }
      }
      .navigationTitle("Aster Vega")
      .navigationDestination(for: String.self) { id in
        if id == Self.pasteDestination {
          PasteSpecView()
        } else if let chart = charts.first(where: { $0.id == id }) {
          ChartDetail(chart: chart)
        }
      }
    }
    .task {
      // Listing is file I/O and compiling is the engine — both off the main thread, and the list is
      // shown before any of the compiling starts.
      if charts.isEmpty { charts = await SpecLibrary.list() }

      // Navigation first. A deep link should open its chart at once rather than queue behind fifteen
      // compiles — and the statuses below are only a subtitle.
      if let requested = requestedChart,
        requested == Self.pasteDestination || charts.contains(where: { $0.id == requested })
      {
        path = [requested]
      }

      for spec in charts where statuses[spec.id] == nil {
        statuses[spec.id] = await SpecLibrary.status(of: spec, loader: SpecLibrary.loader)
      }
    }
  }

  private func subtitle(for chart: BundledSpec) -> String {
    guard let status = statuses[chart.id] else { return "…" }
    if let failure = status.failure { return failure }
    let count = status.diagnostics
    return count == 0 ? "compiled cleanly" : "\(count) diagnostic\(count == 1 ? "" : "s")"
  }
}

/// One bundled chart: drawn, with its controls if it declares any.
///
/// It goes through the same `ChartSession` the paste screen uses rather than the scene `SpecLibrary`
/// already compiled, because a specification with a `bind` has to be *re*compiled when a reader moves
/// the control — and two of the bundled ones do declare bindings. One path for both screens means a
/// control cannot work in one and not the other.
private struct ChartDetail: View {
  let chart: BundledSpec
  @State private var session = ChartSession()

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        if session.loading && session.scene == nil {
          // Said out loud because a dataset Vega serves rather than the bundle takes a moment, and a
          // blank frame is indistinguishable from a chart that drew nothing.
          HStack(spacing: 8) {
            ProgressView()
            Text("Loading data…").foregroundStyle(Color.secondary)
          }
          .frame(maxWidth: .infinity, alignment: .leading)
        }

        if let scene = session.scene {
          SceneCanvas(scene: scene)
            .frame(maxWidth: .infinity)
            .background(Color(white: 0.97))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.secondary.opacity(0.3)))
          Text(summary(of: scene))
            .font(.caption)
            .foregroundStyle(Color.secondary)
        } else {
          Text(session.failure ?? "no scene")
            .foregroundStyle(Color.red)
        }

        if !session.controls.isEmpty {
          ChartControls(controls: session.controls, session: session)
        }

        if !session.diagnostics.isEmpty {
          VStack(alignment: .leading, spacing: 8) {
            Text("Not honoured").font(.headline)
            ForEach(Array(session.diagnostics.enumerated()), id: \.offset) { _, diagnostic in
              VStack(alignment: .leading, spacing: 2) {
                Text(diagnostic.message).font(.callout)
                Text(describe(diagnostic)).font(.caption2).foregroundStyle(Color.secondary)
              }
              .frame(maxWidth: .infinity, alignment: .leading)
            }
          }
        }
      }
      .padding()
    }
    .navigationTitle(chart.name)
    .navigationBarTitleDisplayMode(.inline)
    .task { session.load(specification: chart.json) }
  }

  private func summary(of scene: AsterVega.Scene) -> String {
    "\(Int(scene.width)) × \(Int(scene.height)) points, \(markCount(scene)) marks"
  }

  private func describe(_ diagnostic: VegaDiagnostic) -> String {
    let severity: String
    switch diagnostic.severity {
    case DiagnosticSeverity.fatal: severity = "fatal"
    case DiagnosticSeverity.error: severity = "error"
    case DiagnosticSeverity.warning: severity = "warning"
    default: severity = "info"
    }
    guard let path = diagnostic.jsonPath, !path.isEmpty else { return severity }
    return "\(severity) · \(path)"
  }

  /// Counts the drawable nodes, which is a cheap way to see at a glance that a chart is not empty.
  private func markCount(_ scene: AsterVega.Scene) -> Int {
    func count(_ node: any SceneNode) -> Int {
      guard let group = node as? GroupNode else { return 1 }
      return group.children.reduce(0) { $0 + count($1) }
    }
    return count(scene.root)
  }
}
