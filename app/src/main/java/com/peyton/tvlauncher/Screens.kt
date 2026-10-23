package com.peyton.tvlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.togetherWith
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

interface Actions : AmbientSource {
  fun launch(app: AppEntry)
  fun appInfo(app: AppEntry)
  fun systemSettings()
  fun homeSettings()
  fun inputs(): List<MenuItem>
  fun startAmbient()
  fun wallpaperChanged()
  /** LAN upload server; runs only while the "Add photos from phone" screen is open. */
  fun startPhotoServer()
  fun stopPhotoServer()
  /** Same LAN server in "update" mode; runs only while the Update screen is open. */
  fun startUpdateServer()
  fun stopUpdateServer()
  fun openInstallPermission()
  fun installPending()
  /** GitHub Releases updater: manual check, then download + verify + PackageInstaller. Defaults keep test fakes short. */
  fun checkGitHub() {}
  /** Looks up the ZIP and, if it resolves, saves it as the weather location. */
  fun setZip(zip: String) {}
  fun installFromGitHub() {}
  /** Same LAN server in "API.Bible key" mode; runs only while that screen is open. */
  fun startKeyServer() {}
  fun stopKeyServer() {}
  /** Forgets the key and deletes every cached verse. */
  fun removeVerseKey() {}
  /** FUMS report for a verse that is on screen (deduplicated per foreground session). */
  fun reportFums(token: String) {}
  fun deletePhoto(p: Photo)
  fun deleteAllPhotos()
  /** Blocking; called off the main thread. */
  fun loadThumb(p: Photo): ImageBitmap?
}

@Composable
fun LauncherRoot(st: LauncherState, actions: Actions) {
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    when (st.screen) {
      Screen.HOME -> HomeV2(st, actions)
      Screen.SETTINGS -> SettingsScreen(st, actions)
      Screen.PHONE_UPLOAD -> PhoneUploadScreen(st, actions)
      Screen.MY_PHOTOS -> MyPhotosScreen(st, actions)
      Screen.UPDATE -> UpdateScreen(st, actions)
      Screen.LAYOUT -> LayoutSettings(st, actions)
      Screen.CARDS -> CardSettings(st, actions)
      Screen.CLOCK -> ClockSettings(st, actions)
      Screen.WEATHER_ZIP -> ZipScreen(st, actions)
      Screen.VERSE_KEY -> VerseKeyScreen(st, actions)
    }
    val a by animateFloatAsState(if (st.ambient) 1f else 0f, tween(if (st.ambient) 1600 else 350), label = "amb")
    if (st.ambient || a > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = a }) {
      AmbientScreen(remember(st.ambientSource, st.myPhotos, st.wallpapers) { Photos.forAmbient(st.ambientSource, st.myPhotos, st.wallpapers) }, actions,
        if (st.showWeather) st.weather else null, st.use24h, st.celsius, st.clock.takeIf { it.inAmbient }, st.autoEnhance, st.place?.label ?: "")
    }
  }
}

// ================================================================= SETTINGS

@Composable
fun SettingsScreen(st: LauncherState, actions: Actions) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(first) }
  val idleOpts = listOf(0, 1, 2, 5, 10, 15, 30, 60)
  fun idleLabel(m: Int) = if (m == 0) "Never" else if (m == 60) "1 hour" else "$m minute${if (m == 1) "" else "s"}"
  fun cycleIdle(d: Int) { val i = idleOpts.indexOf(st.idleMinutes).coerceAtLeast(0); st.setIdle(idleOpts[(i + d + idleOpts.size) % idleOpts.size]) }
  var confirmRemoveKey by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
      Column(Modifier.width(280.dp)) {
        T("Settings", 46.sp, weight = FontWeight.ExtraLight)
        Spacer(Modifier.height(10.dp))
        T("TV Launcher ${st.versionLabel}", 15.sp, color = Ink.copy(alpha = 0.7f))
        // Non-blocking notice from the daily GitHub check; installing stays a deliberate choice under System.
        st.gh.release?.takeIf { st.gh.phase == GhPhase.AVAILABLE }?.let { r ->
          Spacer(Modifier.height(10.dp))
          T("●  Update ${r.versionName} available", 13.sp,
            Modifier.clip(RoundedCornerShape(50)).background(Color(0x337BE0A0)).padding(horizontal = 12.dp, vertical = 5.dp), color = Color(0xFF7BE0A0), weight = FontWeight.Medium, maxLines = 1)
          T("Install it from System › Update launcher.", 13.sp, Modifier.padding(start = 4.dp, top = 6.dp), color = Ink.copy(alpha = 0.62f), maxLines = 2)
        }
        Spacer(Modifier.height(28.dp))
        T("Send ambient photos from your phone with \"Add photos from phone\". They're stored privately in the launcher; it never reads the TV's file storage.", 13.sp, color = Ink.copy(alpha = 0.62f), maxLines = 6)
        Spacer(Modifier.height(14.dp))
        T(st.place?.let { "Weather for ${it.label} by Open-Meteo." } ?: "Set your ZIP code under Display › Weather location for the forecast.", 13.sp, color = Ink.copy(alpha = 0.62f), maxLines = 2)
        Spacer(Modifier.height(14.dp))
        T("Press BACK to return home.", 13.sp, color = Ink.copy(alpha = 0.62f))
      }
      Spacer(Modifier.width(36.dp))
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Section("Ambient screen")
        SettingRow("Start after idle", idleLabel(st.idleMinutes), { cycleIdle(1) }, { cycleIdle(-1) }, { cycleIdle(1) }, first)
        SettingRow("Start ambient now", null, { actions.startAmbient() })
        val srcNames = listOf("My photos", "Built-in", "Both")
        fun cycleSrc(d: Int) = st.changeAmbientSource((st.ambientSource + d + 3) % 3)
        SettingRow("Ambient photos", srcNames[st.ambientSource.coerceIn(0, 2)] + if (st.ambientSource == 0 && st.myPhotos.isEmpty()) " (none yet → built-in)" else "",
          { cycleSrc(1) }, { cycleSrc(-1) }, { cycleSrc(1) })
        SettingRow("Add photos from phone", "Scan QR", { st.screen = Screen.PHONE_UPLOAD })
        SettingRow("Manage my photos", "${st.myPhotos.size}", { st.screen = Screen.MY_PHOTOS })
        SettingRow("Auto-enhance photos", if (st.autoEnhance) "On" else "Off", { st.changeEnhance(!st.autoEnhance) }, { st.changeEnhance(!st.autoEnhance) }, { st.changeEnhance(!st.autoEnhance) })
        SettingRow("Clock style in ambient", if (st.clock.inAmbient) "On" else "Off", { st.changeClock(st.clock.copy(inAmbient = !st.clock.inAmbient)) },
          { st.changeClock(st.clock.copy(inAmbient = !st.clock.inAmbient)) }, { st.changeClock(st.clock.copy(inAmbient = !st.clock.inAmbient)) })
        Section("Home screen")
        SettingRow("Home layout", st.layout.title, { st.screen = Screen.LAYOUT })
        SettingRow("Home cards", "${st.cards.count { it.value != CardSlot.OFF }} shown", { st.screen = Screen.CARDS })
        SettingRow("Clock style", "${st.clock.style.title.substringBefore(" ·")} · ${st.clock.font.title}", { st.screen = Screen.CLOCK })
        SettingRow("Edit dock", "${st.favoriteApps.size} apps", { st.screen = Screen.HOME; enterDockEdit(st) })
        Section("Wallpaper")
        val wp = st.wallpapers
        fun cycleWp(d: Int) { if (wp.isEmpty()) return; val n = wp.size + 1; st.changeWallpaper(((st.wallpaperChoice + 1 + d + n) % n) - 1); actions.wallpaperChanged() }
        SettingRow("Wallpaper", if (st.wallpaperChoice !in wp.indices) "Rotate daily" else Photos.nameOf(wp[st.wallpaperChoice]), { cycleWp(1) }, { cycleWp(-1) }, { cycleWp(1) })
        Section("Display")
        SettingRow("Weather location", st.place?.fullLabel ?: "Set ZIP code", { st.zipStatus = null; st.screen = Screen.WEATHER_ZIP })
        SettingRow("Clock", if (st.use24h) "24-hour" else "12-hour", { st.set24h(!st.use24h) }, { st.set24h(!st.use24h) }, { st.set24h(!st.use24h) })
        SettingRow("Weather in ambient", if (st.showWeather) "On" else "Off", { st.changeShowWeather(!st.showWeather) }, { st.changeShowWeather(!st.showWeather) }, { st.changeShowWeather(!st.showWeather) })
        SettingRow("Temperature", if (st.celsius) "°C" else "°F", { st.changeCelsius(!st.celsius) }, { st.changeCelsius(!st.celsius) }, { st.changeCelsius(!st.celsius) })
        Section("Verse of the day")
        val verseSlot = st.cards[HomeCard.VERSE] ?: CardSlot.OFF
        fun cycleVerse(d: Int) { val sl = CardSlot.values(); val m = LinkedHashMap(st.cards); m[HomeCard.VERSE] = sl[(sl.indexOf(verseSlot) + d + sl.size) % sl.size]; st.changeCards(m) }
        SettingRow(if (st.verseKey) "Replace API.Bible key" else "Add API.Bible key", if (st.verseKey) "Saved · ${st.verseAbbr}" else "Scan QR", { st.keyUi = null; st.screen = Screen.VERSE_KEY })
        SettingRow("Show on home", verseSlot.title, { cycleVerse(1) }, { cycleVerse(-1) }, { cycleVerse(1) })
        val flipNotice = { st.changeVerseNotice(!st.verseNotice) }
        SettingRow("Show copyright on card", if (st.verseNotice) "On" else "Off", flipNotice, flipNotice, flipNotice)
        // Credits: the full copyright line from API.Bible and the Starter plan's required citation of api.bible.
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
          T("ABOUT & CREDITS", 11.sp, weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.5f), spacing = 1.5.sp)
          T("Powered by API.Bible — api.bible", 14.sp, Modifier.padding(top = 4.dp), weight = FontWeight.Medium, color = Ink.copy(alpha = 0.85f))
          T(st.verse?.copyright?.takeIf { it.isNotBlank() } ?: if (st.verseKey) "The publisher's copyright notice appears here once today's verse has been fetched."
            else "Scripture text comes from API.Bible with your key; its copyright notice appears here.", 12.5.sp, Modifier.padding(top = 4.dp), color = Ink.copy(alpha = 0.62f), maxLines = 5)
        }
        if (st.verseKey) SettingRow("Remove API.Bible key", null, { confirmRemoveKey = true })
        T(when {
          !st.verseKey -> "NIV text from API.Bible with your own free key (api.bible). Nothing is bundled in the app."
          st.verse != null -> "Today: ${st.verse!!.reference} · ${st.verseAbbr}. Kept on this TV for at most 30 days."
          else -> (st.verseStatus ?: VerseStatus.LOADING).message
        }, 13.sp, Modifier.padding(start = 20.dp, top = 6.dp), color = if (st.verseStatus != null && st.verse == null) Color(0xFFFFB4A8) else Ink.copy(alpha = 0.62f), maxLines = 2)
        Section("Apps")
        val hidden = st.apps.filter { it.pkg in st.hidden }
        if (hidden.isEmpty()) SettingRow("Hidden apps", "None", {})
        else hidden.forEach { app -> SettingRow("Unhide ${app.label}", "Hidden", { st.unhide(app.pkg) }) }
        Section("System")
        val ghNew = st.gh.release?.takeIf { st.gh.phase == GhPhase.AVAILABLE }
        SettingRow("Update launcher", if (ghNew != null) "●  ${ghNew.versionName} available" else st.versionLabel.ifEmpty { "From phone" }, { st.screen = Screen.UPDATE })
        SettingRow("Android TV settings", null, { actions.systemSettings() })
        SettingRow("Default home app", "Open", { actions.homeSettings() })
        Spacer(Modifier.height(40.dp))
      }
    }
    if (confirmRemoveKey) OverlayMenu("Remove the API.Bible key?", "Cached verses are deleted from the TV too", null, listOf(
      MenuItem("Remove", Icons.Filled.Delete) { actions.removeVerseKey(); confirmRemoveKey = false },
      MenuItem("Cancel", Icons.Filled.ArrowBack) { confirmRemoveKey = false },
    )) { confirmRemoveKey = false }
  }
}

@Composable
private fun Section(title: String) {
  T(title.uppercase(), 12.sp, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.5f), spacing = 2.sp)
}

@Composable
fun SettingRow(title: String, value: String?, onClick: () -> Unit, onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null, fr: FocusRequester? = null, forceFocus: Boolean = false,
               onFocus: (() -> Unit)? = null) {
  var focused by remember { mutableStateOf(forceFocus) }
  val k by animateFloatAsState(if (focused || forceFocus) 1f else 0f, tween(160), label = "sr")
  Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp)
      .graphicsLayer { scaleX = 1f + 0.015f * k; scaleY = 1f + 0.015f * k }
      .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
      .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() }
      .onKeyEvent { e ->
        val n = e.nativeKeyEvent
        when (n.keyCode) {
          android.view.KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let { if (n.action == android.view.KeyEvent.ACTION_DOWN) it(); true } ?: false
          android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let { if (n.action == android.view.KeyEvent.ACTION_DOWN) it(); true } ?: false
          else -> false
        }
      }
      .dpadClick(onClick)
      .focusable()
      .clip(RoundedCornerShape(16.dp))
      .background(lerpColor(Color(0x14FFFFFF), Color(0xF2FFFFFF), k))
      .padding(horizontal = 20.dp, vertical = 15.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val c = lerpColor(Ink, Color(0xFF0B0D14), k)
    T(title, 17.sp, color = c, weight = FontWeight.Medium)
    Spacer(Modifier.weight(1f))
    if (value != null) T(if (onLeft != null && k > 0.5f) "‹  $value  ›" else value, 15.sp, color = c.copy(alpha = 0.7f))
  }
}


// ================================================================= PHOTOS FROM PHONE

@Composable
fun PhoneUploadScreen(st: LauncherState, actions: Actions) {
  DisposableEffect(Unit) { actions.startPhotoServer(); onDispose { actions.stopPhotoServer() } }
  val up = st.upload
  val done = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(done) }
  val qr = remember(up?.url) { up?.url?.let { PhotoServer.qr(it, 480).asImageBitmap() } }
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        T("Add photos from your phone", 34.sp, weight = FontWeight.Light, spacing = (-0.5).sp, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Step("1", "Connect your phone to the same Wi-Fi as the TV.")
        Step("2", "Scan the code with your phone's camera.")
        Step("3", "Choose photos from your gallery or Google Photos.")
        Step("4", "Leave this screen open until they finish sending.")
        Spacer(Modifier.height(12.dp))
        if (up?.url != null) {
          T("Or open in a browser", 13.sp, color = Ink.copy(alpha = 0.65f), weight = FontWeight.SemiBold, spacing = 1.sp)
          Spacer(Modifier.height(4.dp))
          T(up.url, 24.sp, weight = FontWeight.Medium)
          Spacer(Modifier.height(4.dp))
          T("Code  ${up.pin}", 16.sp, color = Ink.copy(alpha = 0.75f), spacing = 2.sp)
        } else T(up?.error ?: "Starting…", 18.sp, color = Color(0xFFFFB4A8), maxLines = 3)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (up?.url != null) Color(0xFF7BE0A0) else Color(0x66FFFFFF)))
          Spacer(Modifier.width(10.dp))
          T(if ((up?.received ?: 0) > 0) "${up!!.received} received just now  ·  ${st.myPhotos.size} in My photos" else "Waiting for photos  ·  ${st.myPhotos.size} in My photos", 16.sp, weight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Box(Modifier.width(200.dp)) { SettingRow("Done", null, { st.screen = Screen.SETTINGS }, fr = done) }
          Box(Modifier.width(260.dp)) { SettingRow("Manage photos", "${st.myPhotos.size}", { st.screen = Screen.MY_PHOTOS }) }
        }
        Spacer(Modifier.height(8.dp))
        T("Sharing stops when you leave this screen. Only devices on your home network can connect.", 13.sp, color = Ink.copy(alpha = 0.6f), maxLines = 2)
      }
      Spacer(Modifier.width(48.dp))
      Box(Modifier.size(320.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(18.dp), Alignment.Center) {
        if (qr != null) Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
      }
    }
  }
}

@Composable
private fun Step(n: String, text: String) {
  Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Color(0x2EFFFFFF)), Alignment.Center) { T(n, 14.sp, weight = FontWeight.Bold) }
    Spacer(Modifier.width(14.dp))
    T(text, 16.sp, color = Ink.copy(alpha = 0.92f), maxLines = 2)
  }
}

@Composable
fun MyPhotosScreen(st: LauncherState, actions: Actions) {
  val photos = st.myPhotos
  var menuFor by remember { mutableStateOf<Photo?>(null) }
  var confirmAll by remember { mutableStateOf(false) }
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(first) }
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        T("My photos", 40.sp, weight = FontWeight.Light)
        Spacer(Modifier.width(14.dp))
        T("${photos.size}", 20.sp, color = Ink.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(250.dp)) { SettingRow("Add from phone", null, { st.screen = Screen.PHONE_UPLOAD }, fr = if (photos.isEmpty()) first else null) }
        if (photos.isNotEmpty()) { Spacer(Modifier.width(10.dp)); Box(Modifier.width(200.dp)) { SettingRow("Delete all", null, { confirmAll = true }) } }
      }
      T(if (photos.isEmpty()) "No photos yet. Use \"Add from phone\" to send some." else "Select a photo and press OK to delete it.", 14.sp, Modifier.padding(top = 4.dp, bottom = 16.dp), color = Ink.copy(alpha = 0.65f))
      androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(6.dp),
      ) {
        items(photos.size, key = { photos[it].file ?: it.toString() }) { i ->
          PhotoThumb(photos[i], actions, if (i == 0) first else null) { menuFor = photos[i] }
        }
      }
    }
    menuFor?.let { p ->
      OverlayMenu("Delete this photo?", "It's removed from the TV only", null, listOf(
        MenuItem("Delete", Icons.Filled.Delete) { actions.deletePhoto(p); menuFor = null },
        MenuItem("Cancel", Icons.Filled.ArrowBack) { menuFor = null },
      )) { menuFor = null }
    }
    if (confirmAll) OverlayMenu("Delete all ${photos.size} photos?", "Ambient will use the built-in landscapes", null, listOf(
      MenuItem("Delete all", Icons.Filled.Delete) { actions.deleteAllPhotos(); confirmAll = false },
      MenuItem("Cancel", Icons.Filled.ArrowBack) { confirmAll = false },
    )) { confirmAll = false }
  }
}

@Composable
private fun PhotoThumb(p: Photo, actions: Actions, fr: FocusRequester?, onClick: () -> Unit) {
  val img by produceState<ImageBitmap?>(null, p) { value = withContext(Dispatchers.IO) { actions.loadThumb(p) } }
  var focused by remember { mutableStateOf(false) }
  val k = animateFloatAsState(if (focused) 1f else 0f, androidx.compose.animation.core.spring(0.8f, 420f), label = "pt")
  Box(
    Modifier.aspectRatio(16f / 10f)
      .zIndex(if (focused) 1f else 0f)
      .graphicsLayer { val sc = 1f + 0.08f * k.value; scaleX = sc; scaleY = sc }
      .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
      .onFocusChanged { focused = it.isFocused }
      .dpadClick(onClick)
      .focusable()
      .clip(RoundedCornerShape(14.dp))
      .background(Color(0x22FFFFFF))
      .drawWithContent {
        drawContent()
        drawRoundRect(Color.White, alpha = k.value.coerceIn(0f, 1f), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))
      }
  ) {
    img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    val flag by produceState<String?>(null, p, actions) { value = withContext(Dispatchers.IO) { actions.photoMeta(p)?.flag } }
    flag?.let { T("$it · skipped in ambient", 10.sp, Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 7.dp, vertical = 2.dp), weight = FontWeight.SemiBold, color = Color(0xFFFFD48A)) }
  }
}


// ================================================================= UPDATE FROM PHONE

@Composable
fun UpdateScreen(st: LauncherState, actions: Actions) {
  DisposableEffect(Unit) { actions.startUpdateServer(); onDispose { actions.stopUpdateServer() } }
  val u = st.update ?: UpdateUi()
  val first = remember { FocusRequester() }
  val hasPending = u.phase == UpdatePhase.READY || u.phase == UpdatePhase.NEEDS_PERMISSION || (u.phase == UpdatePhase.ERROR && u.pending != null)
  val qrAction = !st.canInstall || hasPending   // these buttons take the initial focus; otherwise the GitHub row does
  LaunchedEffect(Unit) { delay(50); tryFocus(first) }
  val qr = remember(u.url) { u.url?.let { PhotoServer.qr(it, 480).asImageBitmap() } }
  val prog = animateFloatAsState((u.progress ?: 0f).coerceIn(0f, 1f), tween(250), label = "prog")
  val phaseColor = when (u.phase) {
    UpdatePhase.ERROR -> Color(0xFFFFB4A8); UpdatePhase.DONE, UpdatePhase.CONFIRM, UpdatePhase.READY -> Color(0xFF7BE0A0)
    UpdatePhase.NEEDS_PERMISSION -> Color(0xFFFFD48A); else -> Ink
  }
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        T("Update launcher", 34.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
        Spacer(Modifier.height(4.dp))
        T("Installed: TV Launcher ${st.versionLabel}", 15.sp, color = Ink.copy(alpha = 0.75f))
        Spacer(Modifier.height(12.dp))
        GitHubRow(st, actions, if (qrAction) null else first)
        Spacer(Modifier.height(14.dp))
        T("Or from your phone: scan the code (same Wi-Fi), pick the .apk, then confirm on the TV.", 14.sp, color = Ink.copy(alpha = 0.8f), maxLines = 2)
        Spacer(Modifier.height(8.dp))
        if (u.url != null) {
          T(u.url, 22.sp, weight = FontWeight.Medium)
          T("Code  ${u.pin}", 15.sp, Modifier.padding(top = 2.dp), color = Ink.copy(alpha = 0.75f), spacing = 2.sp)
        } else T(u.serverError ?: "Starting…", 16.sp, color = Color(0xFFFFB4A8), maxLines = 2)
        Spacer(Modifier.height(14.dp))
        // Status card
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 18.dp, vertical = 14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(phaseColor))
            Spacer(Modifier.width(10.dp))
            T(u.message, 15.sp, color = phaseColor, weight = FontWeight.Medium, maxLines = 3)
          }
          if (u.progress != null) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x22FFFFFF))) {
              Box(Modifier.fillMaxSize().graphicsLayer { transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f); scaleX = prog.value }.background(Accent))
            }
          }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          if (!st.canInstall) Box(Modifier.width(330.dp)) { SettingRow("Allow installs first", "Open", { actions.openInstallPermission() }, fr = first) }
          else if (hasPending)
            Box(Modifier.width(300.dp)) { SettingRow("Install ${u.pending?.versionName ?: ""}", null, { actions.installPending() }, fr = first) }
          Box(Modifier.width(180.dp)) { SettingRow("Done", null, { st.screen = Screen.SETTINGS }) }
        }
        Spacer(Modifier.height(8.dp))
        T(if (st.canInstall) "Installs allowed for TV Launcher. Only newer builds signed with the same key are accepted."
          else "One-time setup: allow TV Launcher to install apps (Settings → Apps → Security → Unknown sources).", 12.5.sp, color = Ink.copy(alpha = 0.62f), maxLines = 2)
      }
      Spacer(Modifier.width(48.dp))
      Box(Modifier.size(300.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(18.dp), Alignment.Center) {
        if (qr != null) Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
      }
    }
  }
}

/** "Check GitHub for updates" / "Install x.y from GitHub" row plus one status line; fixed height so nothing jumps. */
@Composable
private fun GitHubRow(st: LauncherState, actions: Actions, fr: FocusRequester?) {
  val g = st.gh
  val rel = g.release
  val title = when {
    g.phase == GhPhase.AVAILABLE && rel != null -> "Install ${rel.versionName} from GitHub"
    g.phase == GhPhase.DOWNLOADING -> "Downloading…"
    else -> "Check GitHub for updates"
  }
  val value = when (g.phase) {
    GhPhase.IDLE -> null; GhPhase.CHECKING -> "Checking…"; GhPhase.UP_TO_DATE -> "Up to date"
    GhPhase.AVAILABLE -> "New"; GhPhase.DOWNLOADING -> null; GhPhase.ERROR -> "Try again"
  }
  val color = when (g.phase) {
    GhPhase.ERROR -> Color(0xFFFFB4A8); GhPhase.AVAILABLE -> Color(0xFF7BE0A0); GhPhase.UP_TO_DATE -> Ink.copy(alpha = 0.8f); else -> Ink.copy(alpha = 0.7f)
  }
  Box(Modifier.width(460.dp)) {
    SettingRow(title, value, { if (g.phase == GhPhase.AVAILABLE) actions.installFromGitHub() else actions.checkGitHub() }, fr = fr)
  }
  T(g.message.ifEmpty { "Updates come from the public GitHub releases; only builds signed with this launcher's key are accepted." },
    13.sp, Modifier.padding(start = 20.dp, top = 6.dp).height(36.dp), color = color, maxLines = 2)
}

// ================================================================= WEATHER LOCATION (ZIP)

/** 5-digit picker for the remote: ▲▼ change the digit, ◀▶ move, number keys type, OK saves. BACK cancels. */
@Composable
fun ZipScreen(st: LauncherState, actions: Actions) {
  val entry = remember { ZipEntry(st.place?.zip) }
  var tick by remember { mutableIntStateOf(0) }   // ZipEntry is plain state; bump to redraw
  val fr = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(fr) }
  val busy = st.zipStatus == "…"
  val err = st.zipStatus?.takeIf { !busy }
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)) {
      T("Weather location", 34.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
      Spacer(Modifier.height(6.dp))
      T("Now: " + (st.place?.fullLabel ?: "not set"), 15.sp, color = Ink.copy(alpha = 0.75f))
      Spacer(Modifier.height(40.dp))
      Row(
        Modifier.focusRequester(fr).focusable()
          .onPreviewKeyEvent { e ->
            val n = e.nativeKeyEvent
            if (n.keyCode == android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
            if (n.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent n.keyCode != android.view.KeyEvent.KEYCODE_BACK
            when (entry.key(n.keyCode)) {
              ZipEntry.Result.CHANGED -> { tick++; if (err != null) st.zipStatus = null; true }
              ZipEntry.Result.SUBMIT -> { if (!busy) actions.setZip(entry.zip); true }
              ZipEntry.Result.NONE -> n.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            }
          },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
      ) {
        @Suppress("UNUSED_EXPRESSION") tick
        for (i in 0 until 5) ZipDigit(entry.digits[i], selected = i == entry.pos)
      }
      Spacer(Modifier.height(26.dp))
      T(when {
        busy -> "Looking up ${entry.zip}…"
        err != null -> err
        else -> "▲ ▼ change a digit  ·  ◀ ▶ move  ·  number keys type  ·  OK saves  ·  BACK cancels"
      }, 15.sp, color = if (err != null) Color(0xFFFFB4A8) else Ink.copy(alpha = 0.72f), weight = FontWeight.Medium, maxLines = 2)
      Spacer(Modifier.height(10.dp))
      T("US ZIP codes only. The ZIP is looked up once (Zippopotam.us) and stored only on this TV; the forecast is from Open-Meteo.",
        12.5.sp, color = Ink.copy(alpha = 0.55f), maxLines = 2)
    }
  }
}

@Composable
private fun ZipDigit(d: Int, selected: Boolean) {
  val k by animateFloatAsState(if (selected) 1f else 0f, tween(160), label = "zd")
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    T("▲", 14.sp, Modifier.graphicsLayer { alpha = k }, color = Ink.copy(alpha = 0.8f))
    Box(
      Modifier.padding(vertical = 6.dp).size(84.dp, 108.dp)
        .graphicsLayer { scaleX = 1f + 0.06f * k; scaleY = 1f + 0.06f * k }
        .clip(RoundedCornerShape(20.dp)).background(lerpColor(Color(0x1FFFFFFF), Color(0xF2FFFFFF), k)),
      Alignment.Center,
    ) {
      androidx.compose.animation.AnimatedContent(d, transitionSpec = {
        val up = targetState == (initialState + 1) % 10
        (androidx.compose.animation.slideInVertically(tween(150)) { h -> if (up) h / 3 else -h / 3 } + androidx.compose.animation.fadeIn(tween(150))) togetherWith
          (androidx.compose.animation.slideOutVertically(tween(150)) { h -> if (up) -h / 3 else h / 3 } + androidx.compose.animation.fadeOut(tween(110)))
      }, label = "digit") { v ->
        T("$v", 56.sp, weight = FontWeight.Light, color = lerpColor(Ink, Color(0xFF0B0D14), k))
      }
    }
    T("▼", 14.sp, Modifier.graphicsLayer { alpha = k }, color = Ink.copy(alpha = 0.8f))
  }
}

// ================================================================= VERSE OF THE DAY: API.BIBLE KEY FROM PHONE

/** QR + PIN page for pasting the API.Bible key on the phone. The LAN server runs only while this screen is open. */
@Composable
fun VerseKeyScreen(st: LauncherState, actions: Actions) {
  DisposableEffect(Unit) { actions.startKeyServer(); onDispose { actions.stopKeyServer() } }
  val k = st.keyUi ?: KeyUi()
  val done = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(done) }
  val qr = remember(k.url) { k.url?.let { PhotoServer.qr(it, 480).asImageBitmap() } }
  val color = when (k.phase) { KeyPhase.ERROR -> Color(0xFFFFB4A8); KeyPhase.SAVED -> Color(0xFF7BE0A0); else -> Ink }
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        T(if (st.verseKey) "Replace your API.Bible key" else "Add your API.Bible key", 34.sp, weight = FontWeight.Light, spacing = (-0.5).sp, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Step("1", "On api.bible: create a free Starter app (non-commercial) and add the NIV.")
        Step("2", "Connect your phone to the same Wi-Fi as the TV and scan the code.")
        Step("3", "Paste the API key and press Save. The TV checks it once.")
        Spacer(Modifier.height(12.dp))
        if (k.url != null) {
          T("Or open in a browser", 13.sp, color = Ink.copy(alpha = 0.65f), weight = FontWeight.SemiBold, spacing = 1.sp)
          Spacer(Modifier.height(4.dp))
          T(k.url, 24.sp, weight = FontWeight.Medium)
          Spacer(Modifier.height(4.dp))
          T("Code  ${k.pin}", 16.sp, color = Ink.copy(alpha = 0.75f), spacing = 2.sp)
        } else T(k.serverError ?: "Starting…", 18.sp, color = if (k.serverError != null) Color(0xFFFFB4A8) else Ink.copy(alpha = 0.75f), maxLines = 3)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (k.phase == KeyPhase.WAITING) (if (k.url != null) Color(0xFF7BE0A0) else Color(0x66FFFFFF)) else color))
          Spacer(Modifier.width(10.dp))
          T(k.message, 16.sp, color = color, weight = FontWeight.Medium, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(200.dp)) { SettingRow("Done", null, { st.screen = Screen.SETTINGS }, fr = done) }
        Spacer(Modifier.height(8.dp))
        T("The key is stored only on this TV (never in backups) and sent only to API.Bible. Sharing stops when you leave this screen.", 13.sp, color = Ink.copy(alpha = 0.6f), maxLines = 2)
      }
      Spacer(Modifier.width(48.dp))
      Box(Modifier.size(320.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(18.dp), Alignment.Center) {
        if (qr != null) Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
      }
    }
  }
}
