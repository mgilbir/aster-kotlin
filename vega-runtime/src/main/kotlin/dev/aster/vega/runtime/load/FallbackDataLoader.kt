package dev.aster.vega.runtime.load

/**
 * Tries several loaders in turn, taking the first that will serve the URI.
 *
 * This exists for the shape almost every published Vega specification has: a **relative** path like
 * `data/barley.json`, which names a file the specification's author had beside it and which this
 * process may or may not. Upstream resolves such a path against the working directory when there is
 * a file system and against `baseURL` otherwise; the same thing here is a local loader followed by
 * a remote one, so a dataset already on disk is read from disk and one that is not is fetched from
 * wherever the host said to look.
 *
 * The order is the policy and is the caller's to state. Disk first is the useful arrangement — it
 * makes a run reproducible and offline once the data is there — but nothing here assumes it.
 *
 * ### What [sanitize] returns, and why it is the input
 *
 * A member's `sanitize` normalizes: a file loader's yields a path relative to its base,
 * [HttpDataLoader]'s yields an absolute URL. There is no one normal form for a chain, because
 * *which* member will serve a URI is not known until one of them succeeds at loading it — a file
 * loader accepts any path inside its directory whether or not the file is there. So [sanitize]
 * answers the question it can answer, "would anything here consider this?", and returns the URI
 * unchanged. That keeps the idempotence the [DataLoader] contract requires, and [load] re-applies
 * every member's policy for real because it calls their `load`, which sanitizes.
 *
 * A refusal names every reason, because "nothing would load this" is not a useful thing to be told
 * when two loaders each had a specific objection.
 *
 * Only a [LoadDeniedException] moves on to the next loader. Anything else — a socket that timed
 * out, a disk that failed — propagates, because it is a failure to *do* the thing rather than a
 * decision not to, and turning it into "nothing would serve this" would hide a network outage
 * behind a policy message.
 */
public class FallbackDataLoader(private val loaders: List<DataLoader>) : DataLoader {

  public constructor(vararg loaders: DataLoader) : this(loaders.toList())

  init {
    require(loaders.isNotEmpty()) { "A fallback loader needs at least one loader to fall back to" }
  }

  override fun sanitize(uri: String): String {
    val refusals = mutableListOf<String>()
    for (loader in loaders) {
      try {
        loader.sanitize(uri)
        return uri
      } catch (denied: LoadDeniedException) {
        refusals += denied.message ?: loader.describe()
      }
    }
    throw LoadDeniedException(refuse(uri, refusals))
  }

  override fun load(uri: String): String {
    val refusals = mutableListOf<String>()
    for (loader in loaders) {
      try {
        return loader.load(uri)
      } catch (denied: LoadDeniedException) {
        refusals += denied.message ?: loader.describe()
      }
    }
    throw LoadDeniedException(refuse(uri, refusals))
  }

  private fun refuse(uri: String, refusals: List<String>): String =
    "No loader would serve '$uri'. " + refusals.joinToString("; ")

  private fun DataLoader.describe(): String = this::class.simpleName ?: "loader"
}
