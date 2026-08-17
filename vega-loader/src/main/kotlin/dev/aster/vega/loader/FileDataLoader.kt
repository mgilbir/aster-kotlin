package dev.aster.vega.loader

import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.LoadDeniedException
import java.io.File

/**
 * Serves data files from one directory on disk.
 *
 * The counterpart to [dev.aster.vega.runtime.load.HttpDataLoader] for local data — an app's own
 * assets, or a corpus of examples — and it has exactly one job beyond reading a file: making sure
 * the file is *inside* [baseDir].
 *
 * A specification names its own paths, so `../../../etc/passwd` is a request the specification gets
 * to make. Path traversal is rejected twice over: the textual form is refused up front, and the
 * resolved canonical path is checked to be under the canonical base, which is what catches a
 * symlink pointing out of the directory. Absolute paths and any URI carrying a scheme are refused
 * outright.
 */
public class FileDataLoader(private val baseDir: File) : DataLoader {

  private val root = baseDir.canonicalFile

  /**
   * Returns the path **relative to [baseDir]**, not the absolute one.
   *
   * Idempotence is the reason, and it is part of the [DataLoader] contract rather than a detail
   * here: callers pass `sanitize`'s output to `load`, and `load` re-checks it. Returning an
   * absolute path would make the loader reject its own output on the second pass.
   */
  override fun sanitize(uri: String): String {
    if (uri.contains("://")) {
      throw LoadDeniedException("This loader reads local files only; '$uri' names a scheme")
    }
    if (uri.startsWith("/") || (uri.length > 1 && uri[1] == ':')) {
      throw LoadDeniedException("Absolute path '$uri' is not allowed")
    }
    val resolved = File(root, uri).canonicalFile
    // The textual check above stops the obvious case; this one stops a symlink out of the tree.
    if (!resolved.path.startsWith(root.path + File.separator) && resolved != root) {
      throw LoadDeniedException("Path '$uri' resolves outside '${root.path}'")
    }
    return resolved.relativeTo(root).path
  }

  override fun load(uri: String): String {
    val file = File(root, sanitize(uri))
    if (!file.isFile) throw LoadDeniedException("No such file: '$uri'")
    return file.readText()
  }
}
