package com.peyton.tvlauncher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** What we need to know about an APK (or the installed app) to decide whether it's a safe self-update. */
data class ApkMeta(val pkg: String, val versionCode: Long, val versionName: String?, val certs: Set<String>)

enum class UpdatePhase { WAITING, RECEIVING, VERIFYING, NEEDS_PERMISSION, READY, CONFIRM, INSTALLING, DONE, ERROR }

/** UI state of the "Update launcher" screen (server address + progress). */
data class UpdateUi(
  val url: String? = null, val pin: String = "", val phase: UpdatePhase = UpdatePhase.WAITING,
  val message: String = "Waiting for the .apk from your phone.", val progress: Float? = null,
  val pending: ApkMeta? = null, val serverError: String? = null,
)

object Updater {
  const val ACTION_STATUS = "com.peyton.tvlauncher.INSTALL_STATUS"

  @Suppress("DEPRECATION")
  private fun certsOf(pi: PackageInfo): Set<String> {
    val sigs = if (Build.VERSION.SDK_INT >= 28) {
      val si = pi.signingInfo ?: return emptySet()
      // Accept the current signer; with key rotation, also any signer in the lineage.
      if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
    } else pi.signatures
    return sigs?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
  }

  fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  @Suppress("DEPRECATION")
  private val sigFlag get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

  /** Test seams (Robolectric can't parse APK archives or signatures); production uses the PackageManager versions. */
  @Volatile internal var inspector: ((Context, File) -> ApkMeta?)? = null
  @Volatile internal var installedProvider: ((Context) -> ApkMeta)? = null

  fun installed(ctx: Context): ApkMeta = installedProvider?.invoke(ctx) ?: installedFromPm(ctx)

  private fun installedFromPm(ctx: Context): ApkMeta {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, sigFlag)
    return ApkMeta(pi.packageName, versionCodeOf(pi), pi.versionName, certsOf(pi))
  }

  @Suppress("DEPRECATION")
  fun versionCodeOf(pi: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

  /** Reads package name, version and signing certs from an APK file without installing it. */
  fun inspect(ctx: Context, f: File): ApkMeta? = inspector?.invoke(ctx, f) ?: inspectWithPm(ctx, f)

  private fun inspectWithPm(ctx: Context, f: File): ApkMeta? = try {
    val pi = ctx.packageManager.getPackageArchiveInfo(f.path, sigFlag)
    pi?.let { ApkMeta(it.packageName, versionCodeOf(it), it.versionName, certsOf(it)) }
  } catch (_: Throwable) { null }

  /** null = OK to install; otherwise a human-readable reason (shown on the TV and the phone). */
  fun check(new: ApkMeta?, cur: ApkMeta): String? = when {
    new == null -> "That file isn't a valid Android app (.apk)."
    new.pkg != cur.pkg -> "That's a different app (${new.pkg}), not TV Launcher."
    new.certs.isEmpty() -> "That APK isn't signed."
    cur.certs.isEmpty() -> "Couldn't read this launcher's own signature, so the update can't be verified."
    new.certs.intersect(cur.certs).isEmpty() -> "That APK is signed with a different key, so it can't update this launcher."
    new.versionCode <= cur.versionCode -> "That's version ${new.versionName ?: new.versionCode} (build ${new.versionCode}); the TV already has ${cur.versionName} (build ${cur.versionCode}). Send a newer build."
    else -> null
  }

  fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

  /** Opens the per-app "Install unknown apps" switch for this launcher (falls back to Security settings). */
  fun permissionIntents(ctx: Context): List<Intent> = listOfNotNull(
    if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")) else null,
    Intent(Settings.ACTION_SECURITY_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
  ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

  /**
   * Streams the APK into a PackageInstaller session and commits; status comes back to MainActivity.onNewIntent.
   * Session bookkeeping (create/commit/abandon) runs on the main thread; only the byte copy runs on IO.
   */
  suspend fun install(ctx: Context, f: File): Int {
    val pi = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(ctx.packageName)
      setSize(f.length())
      if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) // honoured only if allowed; otherwise the TV asks
    }
    val id = withContext(Dispatchers.Main) { pi.createSession(params) }
    try {
      val session = withContext(Dispatchers.Main) { pi.openSession(id) }
      session.use {
        withContext(Dispatchers.IO) {
          session.openWrite("base.apk", 0, f.length()).use { out ->
            f.inputStream().use { it.copyTo(out, 256 * 1024) }
            runCatching { session.fsync(out) }
          }
        }
        withContext(Dispatchers.Main) {
          val intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_STATUS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
          val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
          session.commit(PendingIntent.getActivity(ctx, id, intent, flags).intentSender)
        }
      }
    } catch (t: Throwable) {
      withContext(NonCancellable + Dispatchers.Main) { runCatching { pi.abandonSession(id) } }
      throw t
    }
    return id
  }

  fun pendingFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "verified.apk")
}
