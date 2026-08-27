package dev.aster.vega.runtime.load

/**
 * A [DataLoader] that fetches each URL once per document.
 *
 * There is no incremental dataflow here: every interaction, `setData` and `containerSize` change
 * recompiles the whole specification, and a compile resolves every dataset from scratch. With a
 * loader opted in, that meant **one blocking GET per dataset per tap** — on the dispatching thread,
 * with the loader's own ten- and thirty-second timeouts — and a `{"type": "timer", "throttle":
 * 500}` stream polling the network twice a second. Upstream loads once, because its dataflow only
 * re-runs what changed.
 *
 * So the cache is what makes the recompile-everything trade honest rather than merely slow. It is
 * keyed by the **sanitized** URI, which is what [DataLoader.load] is contracted to take, and it
 * caches successes only: a refusal or a failed fetch is re-attempted, because a network that was
 * down when the chart loaded may be up when the reader taps.
 *
 * Cleared by whoever owns it — `VegaChartController` clears it on `setSpec`, since a new document
 * is a new set of data — so a long-lived controller does not hold one chart's downloads for the
 * lifetime of the next.
 *
 * **Confined**, like everything else the controller owns: see `VegaChartController`'s note on
 * concurrent compiles.
 */
public class CachingDataLoader(private val delegate: DataLoader) : DataLoader {

  private val fetched = LinkedHashMap<String, String>()

  /** How many URLs are held, so a host or a test can see the cache working. */
  public val size: Int
    get() = fetched.size

  override fun sanitize(uri: String): String = delegate.sanitize(uri)

  override fun load(uri: String): String =
    fetched.getOrElse(uri) {
      // Not `getOrPut`: a throw must not leave anything behind, and a failure is retried.
      val text = delegate.load(uri)
      fetched[uri] = text
      text
    }

  /** Forgets everything fetched so far. */
  public fun clear() {
    fetched.clear()
  }
}
