package com.peyton.tvlauncher

import android.service.dreams.DreamService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optional: the same ambient screen as a system screensaver ("Daydream"), so it also appears while other
 * apps are idle. Google TV hides third-party screensavers from Settings; enable with adb (see README).
 */
class AmbientDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
  private val registry = LifecycleRegistry(this)
  private val saved = SavedStateRegistryController.create(this)
  override val lifecycle: Lifecycle get() = registry
  override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

  override fun onCreate() {
    super.onCreate()
    saved.performRestore(null)
    registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    isInteractive = false
    isFullscreen = true
    val prefs = Prefs(this)
    val view = ComposeView(this)
    view.setViewTreeLifecycleOwner(this)
    view.setViewTreeSavedStateRegistryOwner(this)
    window?.decorView?.let { it.setViewTreeLifecycleOwner(this); it.setViewTreeSavedStateRegistryOwner(this) }
    view.setContent {
      var weather by remember { mutableStateOf(if (prefs.showWeather) WeatherApi.parse(prefs.weatherJson) else null) }
      var photos by remember { mutableStateOf(listOf<Photo>()) }
      LaunchedEffect(Unit) {
        photos = withContext(Dispatchers.IO) { Photos.forAmbient(prefs.ambientSource, MyPhotos.list(this@AmbientDream), Photos.builtins(this@AmbientDream)) }
        if (prefs.showWeather) withContext(Dispatchers.IO) { WeatherApi.fetchJson() }?.let { j ->
          WeatherApi.parse(j)?.let { weather = it; prefs.weatherJson = j }
        }
      }
      val src = remember { object : AmbientSource {
        override fun photoMeta(p: Photo) = PhotoMetaStore.get(this@AmbientDream, p)
        override fun loadCover(p: Photo, w: Int, h: Int) = Photos.decodeCover(this@AmbientDream, p, w, h)
      } }
      val look = Look.clock(prefs)
      AmbientScreen(photos, src, weather, prefs.use24h, prefs.celsius, look.takeIf { it.inAmbient }, prefs.sp.getBoolean("enhance", false))
    }
    setContentView(view)
    registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  override fun onDreamingStarted() { super.onDreamingStarted(); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
  override fun onDreamingStopped() { registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); super.onDreamingStopped() }
  override fun onDetachedFromWindow() {
    registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    super.onDetachedFromWindow()
  }
}
