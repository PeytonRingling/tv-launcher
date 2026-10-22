package com.peyton.tvlauncher

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real MainActivity has the cached dock in its state when onCreate returns, i.e. before the first frame is drawn. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ColdStartActivityTest {
  @Test fun dockIsReadyWhenOnCreateReturns() {
    val ctx = RuntimeEnvironment.getApplication()
    val pkgs = listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus")
    ctx.getSharedPreferences("launcher", 0).edit().clear().putString("fav", pkgs.joinToString(",")).putBoolean("seeded", true).commit()
    fun tile(c: Int) = Bitmap.createBitmap(138, 138, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }
    DockCache.save(ctx, pkgs.mapIndexed { i, p -> AppEntry(p, "App $i", null, null, null, Color(0xFF3A3F66), true, tile(0xFF000000.toInt() or (i * 0x404040)).asImageBitmap()) })
    DockCache.saveBackdrop(ctx, tile(0xFF336699.toInt()))
    val act = Robolectric.buildActivity(MainActivity::class.java).create().get()   // no looper idle, no resume
    assertEquals(pkgs, act.st.favoriteApps.map { it.pkg })
    assertEquals(listOf("App 0", "App 1", "App 2"), act.st.favoriteApps.map { it.label })
    act.st.favoriteApps.forEach { assertNotNull(it.tile) }
    assertNotNull(act.st.backdrop); assertNotNull(act.st.wallpaper)
  }
}
