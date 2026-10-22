package com.peyton.tvlauncher

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * GitHub Releases updater: the verification rules (wrong package / wrong cert / not newer / name-vs-APK mismatch) and the
 * full flow in MainActivity (check → download → verify → PackageInstaller session) against a local fake GitHub.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class GitHubUpdateTest {
  private val key = Updater.RELEASE_CERT
  private val other = "0".repeat(64)
  private val installed = ApkMeta("com.peyton.tvlauncher", 7, "4.2", setOf(key))
  private val rel = GhRelease("v4.3", "4.3", 8, "tv-launcher-v4.3-vc8.apk", "https://example.invalid/x.apk", 1)

  @After fun reset() { Updater.inspector = null; Updater.installedProvider = null; GitHubReleases.apiBase = "https://api.github.com" }

  // ---------------------------------------------------------------- rules
  @Test fun acceptsSamePackagePinnedCertNewerBuild() = assertNull(Updater.checkGitHub(ApkMeta("com.peyton.tvlauncher", 8, "4.3", setOf(key)), installed, rel))
  @Test fun rejectsWrongPackage() = assertTrue(Updater.checkGitHub(ApkMeta("com.peyton.toddlergame", 8, "9", setOf(key)), installed, rel)!!.contains("different app"))
  @Test fun rejectsWrongCert() = assertTrue(Updater.checkGitHub(ApkMeta("com.peyton.tvlauncher", 8, "4.3", setOf(other)), installed, rel)!!.contains("different key"))
  @Test fun rejectsUnpinnedCertEvenIfInstalledSharesIt() {
    // e.g. a locally built launcher signed by some other key: the GitHub path still insists on the release key.
    val cur = installed.copy(certs = setOf(other))
    assertTrue(Updater.checkGitHub(ApkMeta("com.peyton.tvlauncher", 8, "4.3", setOf(other)), cur, rel)!!.contains("release key"))
  }
  @Test fun rejectsLowerOrSameVersion() {
    assertTrue(Updater.checkGitHub(ApkMeta("com.peyton.tvlauncher", 7, "4.2", setOf(key)), installed, rel.copy(versionCode = 7))!!.contains("newer build"))
    assertTrue(Updater.checkGitHub(ApkMeta("com.peyton.tvlauncher", 6, "4.1", setOf(key)), installed, rel.copy(versionCode = 6))!!.contains("newer build"))
  }
  @Test fun rejectsApkThatDoesNotMatchTheAssetName() = assertTrue(Updater.checkGitHub(ApkMeta("com.peyton.tvlauncher", 9, "4.4", setOf(key)), installed, rel)!!.contains("release says build 8"))
  @Test fun rejectsUnreadable() = assertTrue(Updater.checkGitHub(null, installed, rel)!!.contains("isn't a valid"))

  // ---------------------------------------------------------------- real APKs (testapks/, see tools/make-testapks.sh)
  private val bt = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/home/box/android-sdk") + "/build-tools/34.0.0"
  private val apks = File("../testapks")
  private fun run(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
  private fun toolInspect(f: File): ApkMeta? {
    val m = Regex("package: name='([^']+)' versionCode='(\\d+)' versionName='([^']*)'").find(run("$bt/aapt", "dump", "badging", f.path)) ?: return null
    val certs = Regex("certificate SHA-256 digest: ([0-9a-f]+)").findAll(run("$bt/apksigner", "verify", "--print-certs", f.path)).map { it.groupValues[1] }.toSet()
    return ApkMeta(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3], certs)
  }
  private fun haveFixtures() = File(apks, "v40.apk").exists() && File(apks, "v41.apk").exists() && File(apks, "v41_otherkey.apk").exists() && File("$bt/aapt").exists()

  @Test fun realApks() {
    assumeTrue("testapks/ missing: run tools/make-testapks.sh", haveFixtures())
    val v40 = toolInspect(File(apks, "v40.apk"))!!; val v41 = toolInspect(File(apks, "v41.apk"))!!; val bad = toolInspect(File(apks, "v41_otherkey.apk"))!!
    val r41 = GhRelease("v4.1", "4.1", 6, "tv-launcher-v4.1-vc6.apk", "https://x/y", 1)
    assertEquals(setOf(key), v41.certs)
    assertNull(Updater.checkGitHub(v41, v40, r41))
    assertTrue(Updater.checkGitHub(bad, v40, r41)!!.contains("different key"))
    assertTrue(Updater.checkGitHub(v40, v41, r41.copy(versionCode = 5))!!.contains("newer build"))
  }

  // ---------------------------------------------------------------- end to end in MainActivity
  private fun idleUntil(timeoutMs: Long = 25_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) { shadowOf(Looper.getMainLooper()).idle(); check(System.currentTimeMillis() < end) { "timeout" }; Thread.sleep(25) }
  }
  private fun releaseJson(base: String, name: String, size: Int) =
    """{"tag_name":"v4.1","assets":[{"name":"$name","browser_download_url":"$base/dl/$name","size":$size}]}"""

  @Test fun endToEnd() {
    assumeTrue("testapks/ missing: run tools/make-testapks.sh", haveFixtures())
    Updater.inspector = { _, f -> toolInspect(f) }
    Updater.installedProvider = { toolInspect(File(apks, "v40.apk"))!! }
    FakeGitHub().use { gh ->
      GitHubReleases.apiBase = gh.base
      val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = ctrl.get()
      val pm = shadowOf(a.packageManager); pm.setCanRequestPackageInstalls(false)
      a.st.screen = Screen.UPDATE

      // 1) rate limited → friendly error, nothing downloaded
      gh.status = 403; gh.json = """{"message":"API rate limit exceeded"}"""; gh.headers = mapOf("X-RateLimit-Remaining" to "0")
      a.checkGitHub(); idleUntil { a.st.gh.phase == GhPhase.ERROR }
      assertTrue(a.st.gh.message, a.st.gh.message.contains("limit"))

      // 2) release signed with another key → found, downloaded, then rejected before PackageInstaller
      gh.status = 200; gh.headers = emptyMap()
      gh.apk = File(apks, "v41_otherkey.apk").readBytes(); gh.json = releaseJson(gh.base, "tv-launcher-v4.1-vc6.apk", gh.apk.size)
      a.checkGitHub(); idleUntil { a.st.gh.phase == GhPhase.AVAILABLE }
      a.installFromGitHub(); idleUntil { a.st.gh.phase == GhPhase.ERROR }
      assertTrue(a.st.gh.message, a.st.gh.message.contains("different key"))
      assertTrue(a.packageManager.packageInstaller.allSessions.isEmpty())
      assertTrue(!Updater.pendingFile(a).exists())

      // 3) same build as installed → up to date, no download
      gh.json = releaseJson(gh.base, "tv-launcher-v4.0-vc5.apk", 1)
      a.checkGitHub(); idleUntil { a.st.gh.phase == GhPhase.UP_TO_DATE }

      // 4) valid v4.1 → verified, waits for "install unknown apps", then PackageInstaller session on return
      gh.apk = File(apks, "v41.apk").readBytes(); gh.json = releaseJson(gh.base, "tv-launcher-v4.1-vc6.apk", gh.apk.size)
      a.checkGitHub(); idleUntil { a.st.gh.phase == GhPhase.AVAILABLE }
      a.installFromGitHub(); idleUntil { a.st.update?.phase == UpdatePhase.NEEDS_PERMISSION }
      assertEquals(6L, a.st.update!!.pending!!.versionCode)
      a.openInstallPermission()
      assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(a).nextStartedActivity?.action)
      pm.setCanRequestPackageInstalls(true)
      ctrl.pause().resume()
      idleUntil { a.packageManager.packageInstaller.allSessions.isNotEmpty() }
      assertEquals("com.peyton.tvlauncher", a.packageManager.packageInstaller.allSessions.last().appPackageName)
      assertTrue(gh.paths.any { it == "/repos/PeytonRingling/tv-launcher/releases/latest" })
      ctrl.pause().stop().destroy()
    }
  }
}
