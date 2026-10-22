package com.peyton.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Snapshot of the dock (labels + the exact tile/banner bitmaps already drawn) in app-private storage, read synchronously in
 * onCreate so the first frame after a cold start shows the full dock. The live PackageManager load replaces it a moment
 * later with pixel-identical entries, so nothing moves.
 */
object DockCache {
  private const val DIR = "dock_v1"
  private fun dir(ctx: Context) = File(ctx.filesDir, DIR)
  private fun safe(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")

  /** Blocking, but small: about a dozen ~140 px PNGs. Returns entries in saved (dock) order, or empty. */
  fun load(ctx: Context): List<AppEntry> = try {
    val d = dir(ctx); val idx = File(d, "index.json")
    if (!idx.exists()) emptyList() else {
      val arr = JSONArray(idx.readText())
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i); val pkg = o.getString("pkg")
        fun img(suffix: String): ImageBitmap? = File(d, safe(pkg) + suffix).takeIf { it.exists() }
          ?.let { BitmapFactory.decodeFile(it.path) }?.also { it.prepareToDraw() }?.asImageBitmap()
        AppEntry(pkg, o.getString("label"), o.optString("cls").takeIf { it.isNotEmpty() }?.let { ComponentName(pkg, it) },
          banner = img(".b.png"), icon = null, tint = Color(o.getInt("tint")), leanback = o.getBoolean("lb"), tile = img(".t.png"))
      }
    }
  } catch (_: Throwable) { emptyList() }

  /** Call off the main thread. Writes into a temp dir, then swaps it in, so a crash mid-write never leaves a half snapshot. */
  @Synchronized fun save(ctx: Context, apps: List<AppEntry>) {
    try {
      val tmp = File(ctx.filesDir, "$DIR.tmp"); tmp.deleteRecursively(); tmp.mkdirs()
      val arr = JSONArray()
      for (a in apps) {
        fun png(b: ImageBitmap?, suffix: String) { b ?: return
          File(tmp, safe(a.pkg) + suffix).outputStream().use { b.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
        png(a.tile, ".t.png"); png(a.banner, ".b.png")
        arr.put(JSONObject().put("pkg", a.pkg).put("label", a.label).put("cls", a.component?.className ?: "")
          .put("tint", a.tint.toArgb()).put("lb", a.leanback))
      }
      File(tmp, "index.json").writeText(arr.toString())
      val d = dir(ctx); val old = File(ctx.filesDir, "$DIR.old"); old.deleteRecursively()
      if (d.exists()) d.renameTo(old)
      if (!tmp.renameTo(d)) { old.renameTo(d); return }
      old.deleteRecursively()
    } catch (_: Throwable) {}
  }

  // ---- last home backdrop (the 384x216 frosted copy), so glass + wallpaper aren't black on the first frame
  private fun backdropFile(ctx: Context) = File(ctx.filesDir, "backdrop_v1.png")
  fun loadBackdrop(ctx: Context): ImageBitmap? = try {
    backdropFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { it.prepareToDraw() }?.asImageBitmap()
  } catch (_: Throwable) { null }
  fun saveBackdrop(ctx: Context, b: Bitmap) {
    try {
      val f = backdropFile(ctx); val tmp = File(f.path + ".tmp")
      tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
      tmp.renameTo(f)
    } catch (_: Throwable) {}
  }
}
