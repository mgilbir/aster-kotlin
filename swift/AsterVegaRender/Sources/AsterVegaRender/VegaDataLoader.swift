import AsterVega
import Foundation

/// Loads a specification's data: from a local directory if it is there, otherwise from Vega's own site.
///
/// Vega's examples say `"url": "data/cars.json"`, which in a browser resolves against wherever the
/// datasets are served from. This resolves it against a directory the host chose — an app's bundle, a
/// checkout's `test-fixtures` — and falls back to `https://vega.github.io/vega/data/` for anything not
/// bundled, so a pasted example works without the app having to ship every dataset Vega has.
///
/// ### The policy, which is the interesting part
///
/// `DataLoader` denies by default for a reason worth restating: a specification is *data*, often data a
/// reader pasted, and a `url` in it asks this process to fetch an address **the specification chose**.
/// Left open that is a server-side request forgery primitive — `http://169.254.169.254/` reads cloud
/// credentials on an instance, `http://localhost:8080/` reaches whatever else the host is running.
///
/// So the network half is an allow-list of exactly two prefixes, and every other absolute URI is
/// refused. A relative path is reduced to its components with `..` refused rather than normalised, which
/// is stricter than resolving it and easier to be sure about. All of that lives in ``sanitize(uri:)``,
/// which does no I/O and is therefore testable on its own — that split is the engine's design and the
/// reason it can be trusted.
///
/// ### Blocking
///
/// `DataLoader.load` is synchronous because every caller inside the engine is, so the fetch blocks the
/// calling thread. **Compile off the main thread.** A cache keeps that from mattering twice: a bound
/// signal recompiles the whole specification on every change, and without one a slider would refetch a
/// dataset per frame.
/// `@unchecked Sendable` is an assertion, and this is what backs it: the configuration is immutable
/// after `init`, and the only mutable state — the cache — is behind a lock. A loader is deliberately
/// shared across compiles, since its cache is the reason a slider over a remote dataset is usable.
public final class VegaDataLoader: NSObject, DataLoader, @unchecked Sendable {

  /// What a specification's relative URL resolves against — Vega's own site, where its examples' data
  /// lives.
  ///
  /// A specification says `data/cars.json` and this is the base it is joined to, exactly as a browser
  /// would resolve it against the page serving the example. So the base ends at the site root rather
  /// than at `data/`: the path in the specification is taken as given, and a specification referring to
  /// something outside `data/` resolves the same way instead of being quietly rewritten.
  public static let baseURL = "https://vega.github.io/vega/"

  private let localDirectory: URL?
  private let session: URLSession
  // `Foundation.TimeInterval` spelled out: the engine exports a `TimeInterval` of its own — Vega's
  // time unit, the `day`/`month` kind — and a file importing both modules cannot say which it means.
  private let timeout: Foundation.TimeInterval

  /// Fetched contents, so recompiling under a control does not refetch.
  ///
  /// A lock rather than an actor: `load` is called synchronously from Kotlin and cannot await.
  private let cache = Cache()

  /// - Parameters:
  ///   - localDirectory: searched first; `nil` fetches everything.
  ///   - session: injectable so a test can serve its own data.
  ///   - timeout: how long a fetch may take before it becomes a diagnostic.
  public init(
    localDirectory: URL?,
    session: URLSession = .shared,
    timeout: Foundation.TimeInterval = 20
  ) {
    self.localDirectory = localDirectory?.standardizedFileURL
    self.session = session
    self.timeout = timeout
    super.init()
  }

  // MARK: - Policy

  public func sanitize(uri: String) throws -> String {
    let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { throw denial("an empty URI is not a path") }

    // An absolute URL under the base is reduced to the path below it, which is also what a relative URI
    // reduces to — so both forms meet here and `sanitize(sanitize(x)) == sanitize(x)` holds, as the
    // interface requires.
    if trimmed.hasPrefix(Self.baseURL) {
      return try relativePath(String(trimmed.dropFirst(Self.baseURL.count)))
    }

    if let scheme = URL(string: trimmed)?.scheme, !scheme.isEmpty {
      throw denial("'\(trimmed)' is not an allowed address. Only \(Self.baseURL) and paths under it")
    }
    return try relativePath(trimmed)
  }

  private func relativePath(_ path: String) throws -> String {
    guard !path.hasPrefix("/") else { throw denial("'\(path)' is an absolute path") }
    let components = path.split(separator: "/", omittingEmptySubsequences: true)
    guard !components.contains("..") else {
      throw denial("'\(path)' tries to leave the data directory")
    }
    guard !components.isEmpty else { throw denial("'\(path)' names no file") }
    return components.joined(separator: "/")
  }

  // MARK: - Fetching

  public func load(uri: String) throws -> String {
    // Re-applied rather than trusted, exactly as the interface asks: a caller that reached `load`
    // without going through `sanitize` gets the same policy, so there is no unchecked path.
    let path = try sanitize(uri: uri)

    if let cached = cache.value(for: path) { return cached }

    if let local = localDirectory {
      let file = local.appendingPathComponent(path).standardizedFileURL
      // The component check already refuses `..`; this also catches a symlink inside the directory
      // that points out of it.
      if file.path.hasPrefix(local.path),
        let contents = try? String(contentsOf: file, encoding: .utf8)
      {
        cache.store(contents, for: path)
        return contents
      }
    }

    let contents = try fetch(path: path)
    cache.store(contents, for: path)
    return contents
  }

  /// Fetches `path` from Vega's site, blocking until it arrives.
  private func fetch(path: String) throws -> String {
    // The sanitized path is joined to the base as it stands — `data/cars.json` becomes
    // `https://vega.github.io/vega/data/cars.json`, which is where the examples' data is served.
    guard let url = URL(string: Self.baseURL + path) else {
      throw denial("'\(path)' is not a name that can be fetched")
    }

    var result: Result<String, Error>!
    let finished = DispatchSemaphore(value: 0)
    let task = session.dataTask(with: URLRequest(url: url, timeoutInterval: timeout)) {
      data, response, error in
      defer { finished.signal() }
      if let error {
        result = .failure(error)
        return
      }
      let status = (response as? HTTPURLResponse)?.statusCode ?? 0
      guard status == 200 else {
        result = .failure(self.denial("\(url.absoluteString) answered HTTP \(status)"))
        return
      }
      guard let data, let text = String(data: data, encoding: .utf8) else {
        result = .failure(self.denial("\(url.absoluteString) was not text"))
        return
      }
      result = .success(text)
    }
    task.resume()

    // A timeout here as well as on the request, so a hung connection cannot block a thread forever.
    guard finished.wait(timeout: .now() + timeout + 5) == .success else {
      task.cancel()
      throw denial("\(url.absoluteString) timed out")
    }
    return try result.get()
  }

  /// The engine turns whatever a loader throws into a diagnostic, so the message is what a reader sees.
  private func denial(_ message: String) -> Error {
    NSError(
      domain: "dev.aster.vega.load",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: "Could not load: \(message)"]
    )
  }

  /// A small locked dictionary. `load` is called synchronously from Kotlin, so it cannot await an actor.
  private final class Cache {
    private var contents: [String: String] = [:]
    private let lock = NSLock()

    func value(for key: String) -> String? {
      lock.lock()
      defer { lock.unlock() }
      return contents[key]
    }

    func store(_ value: String, for key: String) {
      lock.lock()
      defer { lock.unlock() }
      contents[key] = value
    }
  }
}
