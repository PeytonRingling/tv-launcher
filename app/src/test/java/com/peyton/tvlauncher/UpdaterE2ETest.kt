package com.peyton.tvlauncher

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end test of "Update launcher" against the real MainActivity, LAN server and PackageInstaller session code.
 * Robolectric can't parse APK archives, so APK metadata comes from the real build tools (aapt + apksigner) instead.
 * Uses locally built dummies: v40 (current, build 5), v41 (build 6, same key), v41_otherkey (build 6, different key).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class UpdaterE2ETest {
  private val bt = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/home/box/android-sdk") + "/build-tools/34.0.0"
  private val apks = File("../testapks")

  private fun run(vararg cmd: String): String = ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }

  /** Real APK inspection with the platform build tools. */
  private fun toolInspect(f: File): ApkMeta? {
    val badging = run("$bt/aapt", "dump", "badging", f.path)
    val m = Regex("package: name='([^']+)' versionCode='(\\d+)' versionName='([^']*)'").find(badging) ?: return null
    val certs = Regex("certificate SHA-256 digest: ([0-9a-f]+)").findAll(run("$bt/apksigner", "verify", "--print-certs", f.path)).map { it.groupValues[1] }.toSet()
    return ApkMeta(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3], certs)
  }

  private var lastState: () -> String = { "" }
  @After fun reset() { Updater.inspector = null; Updater.installedProvider = null }

  private fun idleUntil(timeoutMs: Long = 25000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) { shadowOf(Looper.getMainLooper()).idle(); if (System.currentTimeMillis() > end) error("timeout; last state=" + lastState()); Thread.sleep(25) }
  }

  private fun post(base: String, path: String, bytes: ByteArray): Pair<Int, String> {
    val c = URL(base + path).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(bytes.size); c.setRequestProperty("Content-Type", "application/octet-stream")
    c.outputStream.use { it.write(bytes) }
    val code = c.responseCode
    return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
  }
  private fun get(url: String): Pair<Int, String> { val c = URL(url).openConnection() as HttpURLConnection; val code = c.responseCode
    return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "") }

  @Test fun updateFlow() {
    // testapks/ is git-ignored (APKs + throwaway key); regenerate with tools/make-testapks.sh. Skipped, not failed, when absent.
    assumeTrue("testapks/ missing: run tools/make-testapks.sh", File(apks, "v41.apk").exists() && File(apks, "v40.apk").exists() && File(apks, "v41_otherkey.apk").exists() && File("$bt/aapt").exists())
    Updater.inspector = { _, f -> toolInspect(f) }
    Updater.installedProvider = { toolInspect(File(apks, "v40.apk"))!! }   // "installed" = v4.0 build 5, Peyton's key

    val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
    val a = ctrl.get()
    lastState = { "${a.st.update?.phase} ${a.st.update?.message} canInstall=${a.st.canInstall}" }
    val pm = shadowOf(a.packageManager)
    pm.setCanRequestPackageInstalls(false)

    a.st.screen = Screen.UPDATE
    a.startUpdateServer()
    idleUntil { a.st.update?.url != null || a.st.update?.serverError != null }
    val u = a.st.update!!
    println("E2E server: ${u.url} ${u.serverError}")
    val port = Regex(":(\\d+)/").find(u.url ?: "http://x:8765/")!!.groupValues[1]
    val base = "http://127.0.0.1:$port"

    // Wrong PIN, page, status
    assertEquals(403, get("$base/000000").first)
    val page = get("$base/${u.pin}"); assertEquals(200, page.first); assertTrue(page.second.contains("Choose .apk file"))

    // 1) different signing key → rejected
    var r = post(base, "/${u.pin}/apk", File(apks, "v41_otherkey.apk").readBytes())
    println("E2E otherkey -> $r"); assertTrue(r.second.contains("\"ok\":false")); assertTrue(r.second.contains("different key"))
    idleUntil { a.st.update?.phase == UpdatePhase.ERROR }

    // 2) same version as installed → rejected
    r = post(base, "/${u.pin}/apk", File(apks, "v40.apk").readBytes())
    println("E2E same-version -> $r"); assertTrue(r.second.contains("Send a newer build"))

    // 3) garbage → rejected
    r = post(base, "/${u.pin}/apk", ByteArray(4096) { it.toByte() })
    println("E2E garbage -> $r"); assertTrue(r.second.contains("isn't a valid"))

    // 4) valid v4.1 but installs not yet allowed → waits for permission
    r = post(base, "/${u.pin}/apk", File(apks, "v41.apk").readBytes())
    println("E2E v41 -> $r"); assertTrue(r.second.contains("\"ok\":true"))
    idleUntil { a.st.update?.phase == UpdatePhase.NEEDS_PERMISSION }
    println("E2E tv: ${a.st.update!!.message}")
    assertEquals("4.1", a.st.update!!.pending?.versionName)
    println("E2E phone status: " + get("$base/${u.pin}/status").second)

    // "Allow installs first" opens the per-app unknown-sources page
    a.openInstallPermission()
    val perm = shadowOf(a).nextStartedActivity
    println("E2E permission intent: ${perm?.action} ${perm?.data}")
    assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, perm?.action)

    // User allows it and comes back → onResume auto-starts the PackageInstaller session
    pm.setCanRequestPackageInstalls(true)
    ctrl.pause().resume()
    idleUntil { a.packageManager.packageInstaller.allSessions.isNotEmpty() || a.st.update?.phase == UpdatePhase.ERROR }
    println("E2E after resume: ${a.st.update!!.phase} ${a.st.update!!.message}")
    val sessions = a.packageManager.packageInstaller.allSessions
    println("E2E sessions: ${sessions.map { "${it.sessionId} ${it.appPackageName}" }}")
    assertTrue(sessions.isNotEmpty())
    assertEquals("com.peyton.tvlauncher", sessions.last().appPackageName)

    // System asks for confirmation → launcher shows the system prompt
    val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
    ctrl.newIntent(Intent(a, MainActivity::class.java).setAction(Updater.ACTION_STATUS)
      .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm))
    assertEquals(UpdatePhase.CONFIRM, a.st.update!!.phase)
    assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(a).nextStartedActivity?.action)
    println("E2E phone status: " + get("$base/${u.pin}/status").second)

    // Cancel → error with retry; then success
    ctrl.newIntent(Intent(a, MainActivity::class.java).setAction(Updater.ACTION_STATUS).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
    assertEquals(UpdatePhase.ERROR, a.st.update!!.phase); assertNotNull(a.st.update!!.pending)
    ctrl.newIntent(Intent(a, MainActivity::class.java).setAction(Updater.ACTION_STATUS).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
    assertEquals(UpdatePhase.DONE, a.st.update!!.phase)
    println("E2E final: ${a.st.update!!.message}")

    // Leaving the screen stops the server
    a.st.screen = Screen.SETTINGS; a.stopUpdateServer()
    val dead = runCatching { get("$base/${u.pin}") }.isFailure
    println("E2E server stopped: $dead"); assertTrue(dead)
    ctrl.pause().stop().destroy()
  }
}
