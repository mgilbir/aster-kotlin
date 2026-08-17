import SwiftUI

/// The demo app: this engine's specifications, compiled and drawn on iOS by the Swift renderer.
///
/// Deliberately importing SwiftUI and nothing else. `AsterVega` exports a `Scene` of its own — the
/// compiled scene graph — and SwiftUI's `Scene` is a protocol, so a file that imported both would have
/// to qualify every mention of either. The files that do need both do exactly that.
@main
struct AsterVegaDemoApp: App {
  var body: some SwiftUI.Scene {
    WindowGroup {
      GalleryView()
    }
  }
}
