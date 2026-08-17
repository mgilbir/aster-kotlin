package dev.aster.vega.runtime.load

/**
 * Raised when a loader refuses a URI. The message says which policy rejected it and why.
 *
 * A refusal is not a failure to fetch: it is the loader doing its job, and the chart reports it as
 * a diagnostic rather than swallowing it.
 */
public class LoadDeniedException(message: String) : Exception(message)

/**
 * Controls how a specification's external data is fetched.
 *
 * **Loading is denied by default.** A Vega specification is data — often data a user pasted — and a
 * `url` in it is a request that this process make a network call to an address the specification
 * chose. Left open that is a server-side request forgery primitive: `http://169.254.169.254/` reads
 * cloud credentials, `http://localhost:8080/` reaches whatever the host is running. So a host has
 * to opt in, and says exactly what it is opting into.
 *
 * The contract has two halves on purpose, following the same shape as the Go implementation in
 * `mgilbir/aster`:
 * - [sanitize] decides *whether* a URI may be fetched and what it resolves to, and is the whole of
 *   the policy. It runs before anything touches the network.
 * - [load] performs the fetch. It re-applies the policy itself, so calling it directly is still
 *   safe and a redirect cannot escape.
 *
 * Splitting them means the decision can be tested, logged and reasoned about without any I/O, and
 * that a caller cannot accidentally fetch something it never checked.
 *
 * Both methods are `@Throws(LoadDeniedException::class)` for the sake of hosts that are not Kotlin.
 * A Kotlin function that throws needs no annotation, but the Obj-C boundary only grows an error
 * out-parameter when it is told to — without it a loader written in Swift compiled fine and had
 * **no way to refuse anything**, which is the one thing this interface exists to do. The annotation
 * costs Kotlin callers nothing and is what makes the contract expressible from outside.
 */
public interface DataLoader {

  /**
   * Validates a URI and returns what it resolves to.
   *
   * **Must be idempotent**: `sanitize(sanitize(x))` has to equal `sanitize(x)`. Callers pass this
   * output to [load], which re-checks it, so a loader that returned a form it would itself reject
   * would refuse its own output on the second pass.
   *
   * @throws LoadDeniedException if the policy refuses it.
   */
  @Throws(LoadDeniedException::class) public fun sanitize(uri: String): String

  /**
   * Fetches the contents of a URI already passed through [sanitize].
   *
   * Text, not bytes: every format Vega reads — JSON, CSV, TSV, TopoJSON — is textual, and a binary
   * payload would be corrupted on the way through anyway.
   *
   * @throws LoadDeniedException if the policy refuses it, or any other exception if the fetch
   *   fails.
   */
  @Throws(LoadDeniedException::class) public fun load(uri: String): String
}

/**
 * Refuses everything, and is the default.
 *
 * A chart whose data will not load says so through a diagnostic and draws what it can, which is the
 * same treatment every other unsupported thing gets. Silence would be worse: an empty chart looks
 * like an empty dataset.
 */
public object DenyLoader : DataLoader {

  private fun deny(uri: String): Nothing =
    throw LoadDeniedException(
      "Loading '$uri' was denied: no data loader is configured. A specification's URL is a " +
        "request that this process fetch an address the specification chose, so a host has to " +
        "opt in explicitly"
    )

  override fun sanitize(uri: String): String = deny(uri)

  override fun load(uri: String): String = deny(uri)
}
