package com.peyton.tvlauncher

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from public GitHub Releases (no token).
 *
 * Release convention (see RELEASING.md): tag `v<versionName>`, with ONE asset named
 * `<assetPrefix>-v<versionName>-vc<versionCode>.apk`, e.g. `tv-launcher-v4.3-vc8.apk`. The versionCode is read from the
 * asset name, so a check needs a single API call, and it is re-checked against the downloaded APK itself before installing.
 */
data class GhRelease(val tag: String, val versionName: String, val versionCode: Long, val apkName: String, val apkUrl: String, val size: Long)

sealed class GhCheck {
  data class UpToDate(val latest: GhRelease) : GhCheck()
  data class Available(val release: GhRelease) : GhCheck()
  data class Failed(val reason: String) : GhCheck()
}

data class HttpReply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

object GitHubReleases {
  /** Test seam: points the client at a local fake GitHub. */
  @Volatile internal var apiBase = "https://api.github.com"
  const val MAX_APK_BYTES = 150L * 1024 * 1024

  fun latestUrl(repo: String) = "$apiBase/repos/$repo/releases/latest"

  private val ASSET = Regex("""^(.+)-v([0-9][0-9A-Za-z.]*)-vc(\d{1,9})\.apk$""")

  /** Parses an asset name; null unless it follows the convention for [prefix]. */
  fun parseAssetName(name: String, prefix: String): Pair<String, Long>? =
    ASSET.matchEntire(name)?.takeIf { it.groupValues[1] == prefix }?.let { it.groupValues[2] to it.groupValues[3].toLong() }

  /** Picks the APK for this app from a `releases/latest` body (highest versionCode if several match). */
  fun parse(json: String, prefix: String): GhRelease? {
    val o = JSONObject(json)
    val tag = o.optString("tag_name")
    val assets = o.optJSONArray("assets") ?: return null
    var best: GhRelease? = null
    for (i in 0 until assets.length()) {
      val a = assets.optJSONObject(i) ?: continue
      val name = a.optString("name")
      val (vn, vc) = parseAssetName(name, prefix) ?: continue
      val url = a.optString("browser_download_url")
      if (!url.startsWith("https://") && !url.startsWith(apiBase)) continue
      val r = GhRelease(tag, vn, vc, name, url, a.optLong("size", -1))
      if (best == null || r.versionCode > best.versionCode) best = r
    }
    return best
  }

  /** Turns the API reply into a result. Pure, so every error path is unit-tested. */
  fun interpret(reply: HttpReply, prefix: String, installedVc: Long, nowMs: Long = System.currentTimeMillis()): GhCheck {
    val h = reply.headers.mapKeys { it.key.lowercase() }
    return when {
      reply.code == 200 -> {
        val rel = try { parse(reply.body, prefix) } catch (_: Exception) { return GhCheck.Failed("GitHub sent an unexpected reply. Try again later.") }
          ?: return GhCheck.Failed("The latest release has no matching .apk yet.")
        if (rel.versionCode > installedVc) GhCheck.Available(rel) else GhCheck.UpToDate(rel)
      }
      reply.code == 404 -> GhCheck.Failed("No releases have been published yet.")
      (reply.code == 403 || reply.code == 429) && (h["x-ratelimit-remaining"] == "0" || h.containsKey("retry-after") || reply.body.contains("rate limit", true)) -> {
        val reset = h["x-ratelimit-reset"]?.toLongOrNull()?.times(1000)
        val retry = h["retry-after"]?.toLongOrNull()?.times(1000)?.plus(nowMs)
        val mins = ((reset ?: retry ?: (nowMs + 3_600_000)) - nowMs).coerceAtLeast(60_000) / 60_000
        GhCheck.Failed("GitHub's limit for update checks was reached. Try again in about $mins min.")
      }
      reply.code in 500..599 -> GhCheck.Failed("GitHub is having trouble (HTTP ${reply.code}). Try again later.")
      else -> GhCheck.Failed("Couldn't check for updates (HTTP ${reply.code}).")
    }
  }

  private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true
    setRequestProperty("User-Agent", "PeytonRingling-app-updater")
    setRequestProperty("Accept", "application/vnd.github+json")
  }

  /** Blocking; call off the main thread. Never throws. */
  fun fetchLatest(repo: String, prefix: String, installedVc: Long): GhCheck = try {
    val c = open(latestUrl(repo))
    try {
      val code = c.responseCode
      val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
      val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull() ?: "" }
      interpret(HttpReply(code, body, headers), prefix, installedVc)
    } finally { c.disconnect() }
  } catch (_: IOException) {
    GhCheck.Failed("No internet connection. Check the network and try again.")
  } catch (e: Exception) {
    GhCheck.Failed("Couldn't check for updates (${e.javaClass.simpleName}).")
  }

  /** Blocking download to [dest] (via a .part file). Throws IOException with a readable message. */
  fun download(rel: GhRelease, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
    val part = File(dest.path + ".part"); part.delete()
    val c = open(rel.apkUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
    try {
      if (c.responseCode != 200) throw IOException("Download failed (HTTP ${c.responseCode}).")
      val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
      if (total > MAX_APK_BYTES) throw IOException("That download is too large to be an update.")
      c.inputStream.use { inp -> part.outputStream().use { out ->
        val buf = ByteArray(64 * 1024); var read = 0L; var last = 0L
        while (true) {
          val n = inp.read(buf); if (n < 0) break
          read += n; if (read > MAX_APK_BYTES) throw IOException("That download is too large to be an update.")
          out.write(buf, 0, n)
          if (read - last > 256 * 1024) { last = read; onProgress(read, total) }
        }
        if (total > 0 && read != total) throw IOException("The download was cut off. Try again.")
        onProgress(read, read)
      } }
      dest.delete(); if (!part.renameTo(dest)) throw IOException("Couldn't save the download.")
    } catch (e: IOException) { part.delete(); throw e } finally { c.disconnect() }
  }
}
