import Foundation

/// The golden files every host reads, and the one parser for them.
///
/// Written once here and once in Kotlin, with the only job of being byte-identical — the arrangement
/// `test-fixtures/scene-walk` arrived at, for the reason recorded there: two recorders drift, so what
/// sits between them is a golden rather than each other.
enum HostConformance {

  /// The repository, found from this file rather than from a working directory, because `swift test`
  /// runs from the package and the goldens are three levels above it.
  static var repositoryRoot: URL {
    URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()  // AsterVegaRenderTests
      .deletingLastPathComponent()  // Tests
      .deletingLastPathComponent()  // AsterVegaRender
      .deletingLastPathComponent()  // swift
      .deletingLastPathComponent()  // the repository
  }

  static func golden(_ name: String) throws -> String {
    let url = repositoryRoot.appending(path: "test-fixtures/host-conformance/\(name)")
    return try String(contentsOf: url, encoding: .utf8)
  }

  /// `input -> a | b | c` as a pair, skipping comments and blank lines.
  static func cases(_ golden: String) -> [(String, [String])] {
    golden.split(separator: "\n", omittingEmptySubsequences: false).compactMap { raw in
      let line = String(raw)
      guard !line.isEmpty, !line.hasPrefix("#") else { return nil }
      guard let arrow = line.range(of: " -> ") else { return nil }
      let input = String(line[line.startIndex..<arrow.lowerBound])
      let rest = line[arrow.upperBound...].trimmingCharacters(in: .whitespaces)
      let observations =
        rest.isEmpty
        ? []
        : rest.components(separatedBy: " | ").map {
          $0.trimmingCharacters(in: .whitespaces)
        }
      return (input, observations)
    }
  }

  /// `a.png,b.png x3` as the urls and the number of frames.
  static func repeatedCase(_ raw: String) throws -> ([String], Int) {
    guard let at = raw.range(of: " x", options: .backwards),
      let frames = Int(raw[at.upperBound...].trimmingCharacters(in: .whitespaces))
    else {
      throw NSError(domain: "HostConformance", code: 1, userInfo: [
        NSLocalizedDescriptionKey: "not a repeated case: \(raw)"
      ])
    }
    let urls = raw[raw.startIndex..<at.lowerBound]
      .components(separatedBy: ",")
      .map { $0.trimmingCharacters(in: .whitespaces) }
    return (urls, frames)
  }

  /// `200x100 in 400x400` as the scene's size and the slot's.
  static func placementCase(_ raw: String) throws -> ((Double, Double), (Double, Double)) {
    let parts = raw.components(separatedBy: " in ")
    guard parts.count == 2 else {
      throw NSError(domain: "HostConformance", code: 2, userInfo: [
        NSLocalizedDescriptionKey: "not a placement case: \(raw)"
      ])
    }
    func size(_ text: String) throws -> (Double, Double) {
      let wh = text.components(separatedBy: "x").compactMap { Double($0) }
      guard wh.count == 2 else {
        throw NSError(domain: "HostConformance", code: 3, userInfo: [
          NSLocalizedDescriptionKey: "not a size: \(text)"
        ])
      }
      return (wh[0], wh[1])
    }
    return (try size(parts[0]), try size(parts[1]))
  }

  /// Six places, which every case in the goldens reaches exactly.
  static func six(_ value: Double) -> String { String(format: "%.6f", value) }
}
