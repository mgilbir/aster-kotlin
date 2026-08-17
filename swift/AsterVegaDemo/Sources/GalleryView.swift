import AsterVega
import SwiftUI

/// The gallery: pick a specification, see the chart the engine compiled and what it could not honour.
///
/// Showing the diagnostics is the point, not decoration. This engine's discipline is that nothing is
/// silently ignored, and a screen that lists every part of a specification it dropped is the only place
/// that claim is visible to someone who is not reading the test suite.
struct GalleryView: View {
  @State private var charts: [CompiledChart] = []
  @State private var selected: CompiledChart?

  var body: some View {
    NavigationStack {
      // `List { ForEach }` rather than `List(charts)`, because the latter resolves to SwiftUI's
      // *editable* overload — it wants a `Binding` and the error it gives says nothing about that.
      List {
        ForEach(charts) { chart in
          NavigationLink(value: chart.id) {
            VStack(alignment: .leading, spacing: 2) {
              Text(chart.name).font(.body)
              Text(subtitle(for: chart))
                .font(.caption)
                .foregroundStyle(chart.failure == nil ? Color.secondary : Color.red)
            }
          }
        }
      }
      .navigationTitle("Aster Vega")
      .navigationDestination(for: String.self) { id in
        if let chart = charts.first(where: { $0.id == id }) {
          ChartDetail(chart: chart)
        }
      }
    }
    .task {
      if charts.isEmpty { charts = SpecLibrary.load() }
    }
  }

  private func subtitle(for chart: CompiledChart) -> String {
    if let failure = chart.failure { return failure }
    let count = chart.diagnostics.count
    return count == 0 ? "compiled cleanly" : "\(count) diagnostic\(count == 1 ? "" : "s")"
  }
}

private struct ChartDetail: View {
  let chart: CompiledChart

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        if let scene = chart.scene {
          SceneCanvas(scene: scene)
            .frame(maxWidth: .infinity)
            .background(Color(white: 0.97))
            .overlay(
              RoundedRectangle(cornerRadius: 4).stroke(Color.secondary.opacity(0.3))
            )
          Text("\(Int(scene.width)) × \(Int(scene.height)) points, \(markCount(scene)) marks")
            .font(.caption)
            .foregroundStyle(.secondary)
        } else {
          Text(chart.failure ?? "no scene")
            .foregroundStyle(.red)
        }

        if !chart.diagnostics.isEmpty {
          VStack(alignment: .leading, spacing: 8) {
            Text("Not honoured").font(.headline)
            ForEach(Array(chart.diagnostics.enumerated()), id: \.offset) { _, diagnostic in
              VStack(alignment: .leading, spacing: 2) {
                Text(diagnostic.message).font(.callout)
                Text(describe(diagnostic))
                  .font(.caption2)
                  .foregroundStyle(.secondary)
              }
            }
          }
        }
      }
      .padding()
    }
    .navigationTitle(chart.name)
    .navigationBarTitleDisplayMode(.inline)
  }

  /// A diagnostic's severity and where in the specification it came from, as one plain string.
  ///
  /// Built here rather than interpolated into a `Text`, which would take the localised-key overload
  /// and warn about producing a debug description.
  private func describe(_ diagnostic: VegaDiagnostic) -> String {
    let severity: String
    switch diagnostic.severity {
    case DiagnosticSeverity.fatal: severity = "fatal"
    case DiagnosticSeverity.error: severity = "error"
    case DiagnosticSeverity.warning: severity = "warning"
    default: severity = "info"
    }
    // `jsonPath` is nullable in the engine: a diagnostic about the specification as a whole has no
    // path into it.
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
