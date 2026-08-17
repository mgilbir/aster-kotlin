import AsterVega
import SwiftUI
import UniformTypeIdentifiers

/// Exports the chart on screen, through the system share sheet.
///
/// Three formats, from two places: SVG is the engine's own serializer — the one the differential harness
/// compares against upstream — and PNG and PDF are drawn by the same renderer that put the chart on screen.
/// The Android demo has offered all three since Milestone 2; iOS had none, because `vega-svg` was not on the
/// framework's export list.
struct ExportButton: View {
  let scene: AsterVega.Scene
  let name: String

  @State private var exported: ExportedFile?

  var body: some View {
    Menu {
      Button("SVG") { export(.svg) }
      Button("PNG") { export(.png) }
      Button("PDF") { export(.pdf) }
    } label: {
      Label("Export", systemImage: "square.and.arrow.up")
        .font(.caption)
    }
    .sheet(item: $exported) { file in
      ShareSheet(url: file.url)
    }
  }

  private enum Format { case svg, png, pdf }

  private func export(_ format: Format) {
    // Captured before leaving the actor: the scene and the name belong to the view, and the box is the
    // assertion that a compiled scene is safe to hand over — it is immutable and nothing else holds it.
    let payload = Payload(scene: scene, name: name)
    // Off the main thread, because a PNG of a dense chart is the renderer drawing the whole scene again
    // and a share sheet that appears a beat later beats a frame that never came.
    Task.detached(priority: .userInitiated) {
      let written: URL? =
        switch format {
        case .svg:
          Self.write(SceneExport.svg(payload.scene).data(using: .utf8), "svg", payload.name)
        case .png: Self.write(SceneExport.png(payload.scene, scale: 3), "png", payload.name)
        case .pdf: Self.write(SceneExport.pdf(payload.scene), "pdf", payload.name)
        }
      if let written {
        await MainActor.run { exported = ExportedFile(url: written) }
      }
    }
  }

  private nonisolated static func write(
    _ data: Data?,
    _ extension: String,
    _ name: String
  ) -> URL? {
    guard let data else { return nil }
    let url = FileManager.default.temporaryDirectory
      .appendingPathComponent("\(name).\(`extension`)")
    do {
      try data.write(to: url, options: .atomic)
      return url
    } catch {
      return nil
    }
  }

  /// Carries the scene across the actor boundary; immutable, and nothing else holds it.
  private struct Payload: @unchecked Sendable {
    let scene: AsterVega.Scene
    let name: String
  }

  private struct ExportedFile: Identifiable {
    let url: URL
    var id: String { url.path }
  }
}

/// The system share sheet, which is how a file leaves an iOS app.
private struct ShareSheet: UIViewControllerRepresentable {
  let url: URL

  func makeUIViewController(context: Context) -> UIActivityViewController {
    UIActivityViewController(activityItems: [url], applicationActivities: nil)
  }

  func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
