package dev.aster.vega.loader

import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.FallbackDataLoader
import dev.aster.vega.runtime.load.HttpDataLoader
import dev.aster.vega.runtime.load.HttpTransport
import dev.aster.vega.runtime.load.LoadDeniedException
import java.io.File

/**
 * Ready-made loaders for the way published Vega specifications name their data.
 *
 * Nearly every example in the Vega gallery writes `"url": "data/barley.json"` — a **relative**
 * path, meaning "beside the specification". Upstream resolves that against the working directory
 * when it is running somewhere with a file system, and against a configured `baseURL` otherwise.
 * Neither is a decision this engine can make on a specification's behalf, so both are offered and
 * the host says which it wants.
 */
public object VegaDataLoaders {

  /**
   * Where the Vega project publishes the data its own examples refer to.
   *
   * This is the site the gallery is served from, so `data/barley.json` under it is the exact file
   * the example was written against — not a lookalike from some other collection. It is a default
   * for *reaching* the data, never a default for whether to: a host still has to opt in by asking
   * for a loader that uses it.
   */
  public const val VEGA_EXAMPLE_DATA_BASE: String = "https://vega.github.io/vega/"

  /**
   * A relative path is read from [directory] if it is there, and fetched from [baseUrl] if not.
   *
   * This is the arrangement that makes a corpus of examples work: the first run fetches what it is
   * missing, and once the files are on disk every later run is offline and reproducible. Pass
   * `cacheDownloads = true` to make that happen by itself — a fetched file is written under
   * [directory] on the way past, so the second run never asks the network again.
   *
   * The domain is restricted to the host of [baseUrl] by default. That matters more than it looks:
   * a specification chooses its own URLs, so an unrestricted loader following a relative path is a
   * request forgery primitive one redirect away. Pass `allowedDomains` explicitly to widen it, and
   * read [HttpDataLoader] before doing so for untrusted input.
   */
  public fun directoryThenNetwork(
    directory: File,
    baseUrl: String = VEGA_EXAMPLE_DATA_BASE,
    allowedDomains: List<String> = listOfNotNull(hostOf(baseUrl)),
    cacheDownloads: Boolean = false,
    blockPrivateNetworks: Boolean = true,
    /** The socket. Injected so the composition above can be tested without one. */
    transport: HttpTransport = JvmHttpTransport,
  ): DataLoader {
    val remote =
      HttpDataLoader(
        transport = transport,
        allowedDomains = allowedDomains,
        baseUrl = baseUrl,
        blockPrivateNetworks = blockPrivateNetworks,
        resolver = JvmHostResolver,
      )
    return FallbackDataLoader(
      FileDataLoader(directory),
      if (cacheDownloads) CachingDataLoader(remote, directory) else remote,
    )
  }

  /** The host part of a URL, for the default allowlist. Null if it has none. */
  private fun hostOf(url: String): String? =
    url.substringAfter("://", "").substringBefore('/').substringBefore(':').takeIf {
      it.isNotEmpty()
    }
}

/**
 * Writes whatever [delegate] fetches into [directory], under the path the specification asked for.
 *
 * The point is to make the *second* run offline. A corpus of examples is fetched once, reviewed,
 * and from then on read from disk — which is what lets a differential test compare against a
 * checked-in reference without a network connection.
 *
 * Writing is best-effort: a read-only directory means the fetch still succeeds and simply is not
 * cached, because failing a load that worked would be the wrong trade. The path is re-checked
 * through [FileDataLoader] before anything is written, so a specification cannot name
 * `../../etc/passwd` and have a fetched payload land there.
 */
public class CachingDataLoader(private val delegate: DataLoader, directory: File) : DataLoader {

  private val files = FileDataLoader(directory)
  private val root = directory.canonicalFile

  override fun sanitize(uri: String): String = delegate.sanitize(uri)

  override fun load(uri: String): String {
    val body = delegate.load(uri)
    val relative =
      try {
        files.sanitize(uri)
      } catch (denied: LoadDeniedException) {
        // Not a path this directory could hold — an absolute URL, or one pointing outside it. The
        // data is still good; there is simply nowhere here to keep it.
        return body
      }
    val target = File(root, relative)
    runCatching {
      target.parentFile?.mkdirs()
      target.writeText(body)
    }
    return body
  }
}
