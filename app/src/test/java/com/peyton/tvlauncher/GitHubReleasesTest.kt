package com.peyton.tvlauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Release JSON parsing, asset picking, version comparison and every error path of the GitHub Releases client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubReleasesTest {
  private val P = "tv-launcher"
  private fun asset(name: String, url: String = "https://github.com/PeytonRingling/tv-launcher/releases/download/t/$name", size: Long = 1234) =
    """{"name":"$name","browser_download_url":"$url","size":$size,"content_type":"application/vnd.android.package-archive"}"""
  private fun release(tag: String, vararg assets: String) = """{"tag_name":"$tag","name":"$tag","draft":false,"prerelease":false,"body":"notes","assets":[${assets.joinToString(",")}]}"""

  @After fun reset() { GitHubReleases.apiBase = "https://api.github.com" }

  @Test fun assetNameConvention() {
    assertEquals("4.3" to 8L, GitHubReleases.parseAssetName("$P-v4.3-vc8.apk", P))
    assertNull(GitHubReleases.parseAssetName("other-app-v4.3-vc8.apk", P))          // another app's APK
    assertNull(GitHubReleases.parseAssetName("$P-v4.3-vc8.apk.idsig", P))          // signature sidecar
    assertNull(GitHubReleases.parseAssetName("$P-v4.3.apk", P))                     // no versionCode
    assertNull(GitHubReleases.parseAssetName("$P-v4.3-vcabc.apk", P))
    assertNull(GitHubReleases.parseAssetName("app-release.apk", P))
  }

  @Test fun parsePicksOurApkAndIgnoresOthers() {
    val json = release("v4.3", asset("$P-v4.3-vc8.apk.idsig"), asset("notes.txt"), asset("other-app-v99-vc99.apk"), asset("$P-v4.3-vc8.apk", size = 4242))
    val r = GitHubReleases.parse(json, P)!!
    assertEquals("v4.3", r.tag); assertEquals("4.3", r.versionName); assertEquals(8L, r.versionCode)
    assertEquals("$P-v4.3-vc8.apk", r.apkName); assertEquals(4242L, r.size)
    assertTrue(r.apkUrl.startsWith("https://github.com/"))
  }

  @Test fun parsePicksHighestVersionCodeWhenSeveral() {
    val r = GitHubReleases.parse(release("v4.3", asset("$P-v4.2-vc7.apk"), asset("$P-v4.3-vc8.apk")), P)!!
    assertEquals(8L, r.versionCode)
  }

  @Test fun parseRejectsPlainHttpDownloads() {
    assertNull(GitHubReleases.parse(release("v4.3", asset("$P-v4.3-vc8.apk", url = "http://evil.example/x.apk")), P))
  }

  @Test fun newerIsAvailable_sameOrOlderIsUpToDate() {
    val ok = HttpReply(200, release("v4.3", asset("$P-v4.3-vc8.apk")))
    assertTrue(GitHubReleases.interpret(ok, P, 8L - 1) is GhCheck.Available)
    assertTrue(GitHubReleases.interpret(ok, P, 8L) is GhCheck.UpToDate)
    assertTrue(GitHubReleases.interpret(ok, P, 8L + 5) is GhCheck.UpToDate)
  }

  @Test fun releaseWithoutApk() {
    val r = GitHubReleases.interpret(HttpReply(200, release("v4.3", asset("notes.txt"))), P, 1) as GhCheck.Failed
    assertTrue(r.reason, r.reason.contains("no matching .apk"))
  }

  @Test fun noReleaseYet() {
    val r = GitHubReleases.interpret(HttpReply(404, """{"message":"Not Found"}"""), P, 1) as GhCheck.Failed
    assertTrue(r.reason.contains("No releases"))
  }

  @Test fun rateLimited403UsesResetHeader() {
    val now = 1_700_000_000_000L
    val reply = HttpReply(403, """{"message":"API rate limit exceeded for 1.2.3.4."}""",
      mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to ((now / 1000) + 25 * 60).toString()))
    val r = GitHubReleases.interpret(reply, P, 1, now) as GhCheck.Failed
    assertTrue(r.reason, r.reason.contains("limit") && r.reason.contains("25 min"))
  }

  @Test fun rateLimited429RetryAfter() {
    val r = GitHubReleases.interpret(HttpReply(429, "", mapOf("Retry-After" to "120")), P, 1, 0L) as GhCheck.Failed
    assertTrue(r.reason, r.reason.contains("limit") && r.reason.contains("2 min"))
  }

  @Test fun forbiddenWithoutRateLimitIsGenericError() {
    val r = GitHubReleases.interpret(HttpReply(403, """{"message":"Forbidden"}"""), P, 1) as GhCheck.Failed
    assertTrue(r.reason, r.reason.contains("HTTP 403"))
  }

  @Test fun serverErrorAndGarbage() {
    assertTrue((GitHubReleases.interpret(HttpReply(502, "<html>"), P, 1) as GhCheck.Failed).reason.contains("trouble"))
    assertTrue((GitHubReleases.interpret(HttpReply(200, "<html>not json"), P, 1) as GhCheck.Failed).reason.contains("unexpected"))
  }

  @Test fun noNetwork() {
    GitHubReleases.apiBase = "http://127.0.0.1:1"   // nothing listens on port 1
    val r = GitHubReleases.fetchLatest("PeytonRingling/tv-launcher", P, 1) as GhCheck.Failed
    assertTrue(r.reason, r.reason.contains("No internet"))
  }

  @Test fun fetchAndDownloadAgainstFakeGitHub() {
    val apk = ByteArray(700_000) { (it % 251).toByte() }
    FakeGitHub().use { gh ->
      gh.json = release("v4.3", asset("$P-v4.3-vc8.apk", url = gh.base + "/dl/x.apk", size = apk.size.toLong()))
      gh.apk = apk
      GitHubReleases.apiBase = gh.base
      val rel = (GitHubReleases.fetchLatest("PeytonRingling/tv-launcher", P, 8L - 1) as GhCheck.Available).release
      assertEquals("/repos/PeytonRingling/tv-launcher/releases/latest", gh.paths.first())
      val dest = File.createTempFile("ghdl", ".apk"); var last = 0L
      GitHubReleases.download(rel, dest) { read, _ -> last = read }
      assertTrue(dest.readBytes().contentEquals(apk)); assertEquals(apk.size.toLong(), last)
      // rate limited
      gh.status = 403; gh.headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to ((System.currentTimeMillis() / 1000) + 600).toString())
      gh.json = """{"message":"API rate limit exceeded"}"""
      assertTrue((GitHubReleases.fetchLatest("PeytonRingling/tv-launcher", P, 1) as GhCheck.Failed).reason.contains("limit"))
      // download that's cut off / missing
      gh.status = 200; gh.apkStatus = 404
      val r = runCatching { GitHubReleases.download(rel, dest) }
      assertTrue(r.exceptionOrNull() is IOException)
    }
  }
}
