package com.peyton.tvlauncher

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders v2 at 1920x1080 (960x540dp @ xhdpi, television) with realistic fake app icons (screenshots only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ScreensTest {
  @get:Rule val rule = createComposeRule()
  private val out = "../screens/v3"
  private val ctx get() = RuntimeEnvironment.getApplication()

  private fun res(name: String): android.graphics.Bitmap? = javaClass.classLoader!!.getResourceAsStream("fake/$name.png")?.use { BitmapFactory.decodeStream(it) }

  // ---- fake icon "types", like real TV apps ship them
  private fun padded(src: android.graphics.Bitmap, scale: Float = 0.72f, round: Float = 0.22f, white: Boolean = false): android.graphics.Bitmap {
    val px = 192; val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out); val sz = px * scale; val o = (px - sz) / 2
    val r = android.graphics.RectF(o, o, o + sz, o + sz)
    val path = android.graphics.Path().apply { addRoundRect(r, sz * round, sz * round, android.graphics.Path.Direction.CW) }
    c.save(); c.clipPath(path)
    if (white) { c.drawColor(android.graphics.Color.WHITE); val p2 = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0033.toInt() }
      c.drawRoundRect(android.graphics.RectF(o + sz * 0.22f, o + sz * 0.3f, o + sz * 0.78f, o + sz * 0.7f), sz * 0.08f, sz * 0.08f, p2)
      val tri = android.graphics.Path().apply { moveTo(o + sz * 0.44f, o + sz * 0.39f); lineTo(o + sz * 0.62f, o + sz * 0.5f); lineTo(o + sz * 0.44f, o + sz * 0.61f); close() }
      c.drawPath(tri, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }) }
    else c.drawBitmap(src, null, r, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    c.restore(); return out
  }
  private fun circle(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val px = 192; val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(out)
    val path = android.graphics.Path().apply { addCircle(96f, 96f, 80f, android.graphics.Path.Direction.CW) }
    c.clipPath(path); c.drawBitmap(src, null, android.graphics.RectF(16f, 16f, 176f, 176f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)); return out
  }
  private fun bareLogo(letter: String, color: Int): android.graphics.Bitmap {
    val px = 192; val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(out)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 120f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER }
    c.drawText(letter, 96f, 138f, p)
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    listOf(0xFFFFB400, 0xFFFF6A00, 0xFFE0115F, 0xFF6E3FD8, 0xFF0098E6, 0xFF00B04F).forEachIndexed { i, col -> dot.color = col.toInt(); c.drawCircle(150f, 52f + i * 15f, 6f, dot) }
    return out
  }

  /** type: A = adaptive (full-bleed layers), P = legacy padded rounded square, W = legacy padded white tile,
   *  C = legacy circle, L = bare logo on transparency, B = banner only (no icon). */
  private fun sampleApps(): List<AppEntry> {
    fun a(key: String, pkg: String, label: String, type: Char = 'A', leanback: Boolean = true, banner: Boolean = true): AppEntry {
      val raw = res("icon_$key"); val bn = if (banner) res("banner_$key") else null
      val icon = when (type) {
        'A' -> raw; 'P' -> raw?.let { padded(it) }; 'W' -> padded(raw ?: res("icon_youtube")!!, 0.70f, 0.18f, white = true)
        'C' -> raw?.let { circle(it) }; 'L' -> bareLogo("P", 0xFFFFFFFF.toInt()); else -> null
      }
      return Apps.entry(pkg, label, null, bn, icon, type == 'A', leanback)
    }
    return listOf(
      a("youtube", "com.google.android.youtube.tv", "YouTube"), a("netflix", "com.netflix.ninja", "Netflix", 'P'),
      a("disney", "com.disney.disneyplus", "Disney+", 'C'), a("hulu", "com.hulu.livingroomplus", "Hulu", 'P'),
      a("prime", "com.amazon.amazonvideo.livingroom", "Prime Video"), a("max", "com.hbo.hbonow", "HBO Max", 'P'),
      a("peacock", "com.peacocktv.peacockandroid", "Peacock TV", 'L'), a("plex", "com.plexapp.android", "Plex", 'B'),
      a("ytmusic", "com.google.android.youtube.tvunplugged", "YouTube TV", 'W', banner = false),
      a("spotify", "com.spotify.tv.android", "Spotify"), a("tapzoo", "com.peyton.toddlergame", "Tap Zoo", leanback = false),
      a("appletv", "com.apple.atve.androidtv.appletv", "Apple TV"), a("photos", "com.google.android.apps.photos", "Photos", 'C', false),
      a("settings", "com.android.tv.settings", "Settings", leanback = false), a("games", "com.google.android.play.games", "Play Games", 'P', false),
      a("play", "com.android.vending", "Google Play"), a("espn", "com.espn.score_center", "ESPN"),
      a("pluto", "tv.pluto.android", "Pluto TV"), a("tubi", "com.tubitv", "Tubi"),
    ).sortedBy { it.label.lowercase() }
  }

  private fun state(wall: String = "canyon"): LauncherState {
    ctx.getSharedPreferences("launcher", 0).edit().clear().commit()
    val st = LauncherState(Prefs(ctx))
    st.updateApps(sampleApps())
    // Dock order like Peyton would set it
    listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus", "com.hulu.livingroomplus",
      "com.amazon.amazonvideo.livingroom", "com.hbo.hbonow", "com.peacocktv.peacockandroid", "com.plexapp.android", "com.google.android.youtube.tvunplugged").let { f ->
      st.favorites.forEach { st.toggleFav(it) }; f.forEach { st.toggleFav(it) }
    }
    st.weather = Weather(48.0, 2, 61.0, 34.0, true, System.currentTimeMillis(), listOf(
      Hour("6 PM", 46.0, 2, true), Hour("7 PM", 43.0, 0, false), Hour("8 PM", 41.0, 0, false), Hour("9 PM", 39.0, 3, false), Hour("10 PM", 37.0, 3, false)))
    st.inputs = listOf(InputUi("H1", "PlayStation 5", "HDMI 1 · eARC") {}, InputUi("H2", "Apple TV 4K", "HDMI 2") {}, InputUi("TV", "Live TV", "Antenna & channels") {})
    st.place = WeatherPlace("10001", "New York City", "NY", 40.7484, -73.9967)
    st.wallpapers = Photos.builtins(ctx)
    st.photos = st.wallpapers
    val full = Photos.decode(ctx, Photo(asset = "wallpapers/$wall.jpg"), 1920)!!
    st.wallpaper = full.asImageBitmap(); st.backdrop = Photos.frosted(full).asImageBitmap()
    return st
  }

  private val metas = HashMap<String, PhotoMeta>()
  private val actions = object : Actions {
    override fun launch(app: AppEntry) {}
    override fun appInfo(app: AppEntry) {}
    override fun systemSettings() {}
    override fun homeSettings() {}
    override fun inputs() = listOf(MenuItem("PlayStation 5", null, "HDMI 1") {})
    override fun startAmbient() {}
    override fun wallpaperChanged() {}
    override fun startPhotoServer() {}
    override fun stopPhotoServer() {}
    override fun startUpdateServer() {}
    override fun stopUpdateServer() {}
    override fun openInstallPermission() {}
    override fun installPending() {}
    override fun deletePhoto(p: Photo) {}
    override fun deleteAllPhotos() {}
    override fun loadThumb(p: Photo): ImageBitmap? {
      val i = p.file!!.removePrefix("/fake/").toInt()
      val b = Photos.decode(ctx, Photos.builtins(ctx)[i % 7], 1920)!!
      return android.graphics.Bitmap.createScaledBitmap(b, 480, 270, true).asImageBitmap()
    }
    override fun photoMeta(p: Photo): PhotoMeta? = metas[PhotoMetaStore.keyOf(p)]
    override fun loadCover(p: Photo, w: Int, h: Int) = Photos.decodeCover(ctx, p, w, h)
  }

  private fun shot(name: String, st: LauncherState, settle: Long = 1500, before: (LauncherState) -> Unit = {}) {
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    rule.mainClock.advanceTimeBy(300)
    before(st)
    rule.mainClock.advanceTimeBy(settle)
    Thread.sleep(400)
    rule.mainClock.advanceTimeBy(settle)
    rule.onRoot().captureRoboImage("$out/$name.png")
  }

  @Test fun home() = shot("01_home_dock", state())
  @Test fun wHighlands() = shot("02_home_highlands", state("highlands"))
  @Test fun wCoast() = shot("02_home_coast", state("coast"))
  @Test fun wFalls() = shot("02_home_falls", state("falls"))
  @Test fun wFjord() = shot("02_home_fjord", state("fjord"))
  @Test fun wPeaks() = shot("02_home_peaks", state("peaks"))
  @Test fun wValley() = shot("02_home_valley", state("valley"))
  @Test fun allApps() = shot("03_all_apps_panel", state("highlands")) { it.allApps = true }
  @Test fun allAppsMidSlide() {
    val st = state("highlands")
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    rule.mainClock.advanceTimeBy(1500); Thread.sleep(300); rule.mainClock.advanceTimeBy(500)
    st.allApps = true
    rule.mainClock.advanceTimeBy(48); Thread.sleep(100); rule.mainClock.advanceTimeBy(112)
    rule.onRoot().captureRoboImage("$out/03b_all_apps_mid_slide.png")
  }
  @Test fun menu() = shot("04_app_menu", state()) { it.menuFor = it.apps.first { a -> a.pkg == "com.netflix.ninja" } }
  @Test fun move() = shot("05_dock_move", state()) { it.moving = "com.disney.disneyplus" }
  @Test fun settings() = shot("06_settings", state()) { it.screen = Screen.SETTINGS }
  @Test fun ambient() {
    val st = state(); st.changeAmbientSource(1)
    st.wallpapers = listOf(Photo(asset = "wallpapers/falls.jpg"))
    shot("07_ambient", st, settle = 2500) { it.ambient = true }
  }
  @Test fun upload() = shot("09_add_photos_from_phone", state()) {
    it.upload = UploadUi("http://192.168.1.42:8765/482913", "482913", received = 3); it.myPhotos = List(12) { i -> Photo(file = "/fake/$i") }
    it.screen = Screen.PHONE_UPLOAD
  }
  @Test fun updateScreen() = shot("12_update_launcher", state()) {
    it.versionLabel = "4.0 (build 5)"; it.canInstall = true
    it.update = UpdateUi("http://192.168.1.42:8766/731402", "731402", UpdatePhase.RECEIVING, "Receiving update… 62%", 0.62f)
    it.screen = Screen.UPDATE
  }
  @Test fun updateNeedsPermission() = shot("13_update_needs_permission", state()) {
    it.versionLabel = "4.0 (build 5)"; it.canInstall = false
    it.update = UpdateUi("http://192.168.1.42:8766/731402", "731402", UpdatePhase.NEEDS_PERMISSION,
      "Version 4.1 is ready. Allow installs for TV Launcher on the TV, then press Install.", pending = ApkMeta("com.peyton.tvlauncher", 6, "4.1", emptySet()))
    it.screen = Screen.UPDATE
  }
  @Test fun myPhotos() = shot("10_my_photos", state()) { it.myPhotos = List(14) { i -> Photo(file = "/fake/$i") }; it.screen = Screen.MY_PHOTOS }

  // ======================= v4.1 =======================
  private val out41 = "../screens/v41"
  private fun shot41(name: String, st: LauncherState, before: (LauncherState) -> Unit = {}) {
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    rule.mainClock.advanceTimeBy(300); before(st)
    repeat(4) { rule.mainClock.advanceTimeBy(700); Thread.sleep(300) }
    rule.onRoot().captureRoboImage("$out41/$name.png")
  }
  private fun testPhoto(name: String): Photo {
    val dst = java.io.File(ctx.filesDir, "photos/$name.jpg").apply { parentFile!!.mkdirs() }
    javaClass.classLoader!!.getResourceAsStream("photos/$name.jpg")!!.use { i -> dst.outputStream().use { i.copyTo(it) } }
    return Photo(file = dst.absolutePath)
  }
  /** Face boxes for the test photos (Robolectric can't run android.media.FaceDetector; on the TV it runs at ingest). */
  private val faceBoxes = mapOf(
    "p832" to listOf(NRect(0.37f, 0.20f, 0.72f, 0.47f)),
    "p838" to listOf(NRect(0.53f, 0.36f, 0.64f, 0.44f), NRect(0.37f, 0.37f, 0.51f, 0.46f)),
    "p1027" to listOf(NRect(0.27f, 0.29f, 0.74f, 0.60f)),
    "p64" to listOf(NRect(0.40f, 0.30f, 0.80f, 0.60f)))
  private fun analyzed(name: String): Photo {
    val p = testPhoto(name); val b = BitmapFactory.decodeFile(p.file)!!
    metas[PhotoMetaStore.keyOf(p)] = Analyzer.analyze(b, b.width, b.height, FaceFinder { faceBoxes[name] ?: emptyList() }); return p
  }

  @Test fun layoutGlass() = shot41("L1_layout_glass_dock", state()) { it.changeLayout(HomeLayout.GLASS) }
  @Test fun layoutCinematic() = shot41("L2_layout_cinematic", state("highlands")) { it.changeLayout(HomeLayout.CINEMATIC) }
  @Test fun layoutSideRail() = shot41("L3_layout_side_rail", state("fjord")) { it.changeLayout(HomeLayout.SIDE_RAIL) }
  @Test fun layoutMinimal() = shot41("L4_layout_minimal", state("coast")) { it.changeLayout(HomeLayout.MINIMAL) }
  @Test fun layoutMinimalDockShown() {
    val st = state("coast"); st.changeLayout(HomeLayout.MINIMAL)
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    repeat(3) { rule.mainClock.advanceTimeBy(500); Thread.sleep(200) }
    rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
    repeat(2) { rule.mainClock.advanceTimeBy(400); Thread.sleep(200) }
    rule.onRoot().captureRoboImage("$out41/L4b_layout_minimal_dock_revealed.png")
  }
  @Test fun layoutSettings() = shot41("S1_settings_home_layout", state()) { it.screen = Screen.LAYOUT }

  @Test fun clockStacked() = shot41("C1_clock_stacked_manrope", state("valley")) {
    it.changeClock(ClockLook(ClockStyle.STACKED, ClockFont.MANROPE, ClockWeight.LIGHT))
    it.changeCards(mapOf(HomeCard.WEATHER3 to CardSlot.RIGHT, HomeCard.DATE to CardSlot.LEFT)) }
  @Test fun clockSplit() = shot41("C2_clock_split_outfit", state("peaks")) {
    it.changeClock(ClockLook(ClockStyle.SPLIT, ClockFont.OUTFIT, ClockWeight.BOLD))
    it.changeCountdown(Countdown("Beach trip", 12, 3)); it.changeCards(mapOf(HomeCard.COUNTDOWN to CardSlot.LEFT, HomeCard.TOGGLES to CardSlot.RIGHT)) }
  @Test fun clockWords() = shot41("C3_clock_words_playfair", state("falls")) {
    it.changeClock(ClockLook(ClockStyle.WORDS, ClockFont.PLAYFAIR, ClockWeight.REGULAR))
    it.changeNote("Dinner at 7 — pizza night!"); it.changeCards(mapOf(HomeCard.NOTE to CardSlot.LEFT, HomeCard.WEATHER to CardSlot.RIGHT)) }
  @Test fun clockAnalog() = shot41("C4_clock_analog_space", state("highlands")) {
    it.changeClock(ClockLook(ClockStyle.ANALOG, ClockFont.SPACE, ClockWeight.LIGHT)) }
  @Test fun clockSettings() = shot41("S2_settings_clock_style", state()) {
    it.changeClock(ClockLook(ClockStyle.SPLIT, ClockFont.OUTFIT, ClockWeight.BOLD)); it.screen = Screen.CLOCK }
  @Test fun cardPicker() = shot41("S3_settings_home_cards", state()) {
    it.changeCards(mapOf(HomeCard.WEATHER3 to CardSlot.LEFT, HomeCard.COUNTDOWN to CardSlot.RIGHT, HomeCard.INPUTS to CardSlot.RIGHT))
    it.changeCountdown(Countdown("Beach trip", 12, 3)); it.screen = Screen.CARDS }

  @Test fun dockEditDoneFocused() = shot41("E1b_dock_edit_scrolled_to_done", state()) {
    enterDockEdit(it); val ed = it.dockEdit!!; repeat(ed.doneIndex) { ed.key(EditKey.RIGHT) } }
  @Test fun dockEditPickedUp() = shot41("E1_dock_edit_picked_up", state()) {
    enterDockEdit(it, "com.disney.disneyplus"); it.dockEdit!!.key(EditKey.OK); it.dockEdit!!.key(EditKey.RIGHT) }
  @Test fun dockEditBadge() = shot41("E2_dock_edit_remove_badge", state()) {
    enterDockEdit(it, "com.hbo.hbonow"); it.dockEdit!!.key(EditKey.DOWN) }
  @Test fun dockEditUndo() = shot41("E3_dock_edit_undo_toast", state()) {
    enterDockEdit(it, "com.plexapp.android"); it.dockEdit!!.key(EditKey.MENU) }
  @Test fun dockEditChecklist() = shot41("E4_dock_add_apps_checklist", state()) {
    enterDockEdit(it); val ed = it.dockEdit!!; repeat(ed.addIndex) { ed.key(EditKey.RIGHT) }; ed.key(EditKey.OK) }

  /** Real key events through LauncherRoot: pick up Disney+, move it right twice, drop, remove HBO Max via its badge, exit with BACK. */
  @Test fun dockEditKeysThroughUi() {
    val st = state()
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    rule.mainClock.advanceTimeBy(500)
    enterDockEdit(st, "com.disney.disneyplus")
    repeat(3) { rule.mainClock.advanceTimeBy(200); Thread.sleep(50) }
    fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.mainClock.advanceTimeBy(200) }
    press(Key.DirectionCenter); assertTrue(st.dockEdit!!.picked)
    press(Key.DirectionRight); press(Key.DirectionRight); press(Key.DirectionCenter)
    assertEquals(listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.hulu.livingroomplus", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus"), st.favorites.take(5))
    press(Key.DirectionRight); press(Key.DirectionDown); press(Key.DirectionCenter)   // focus HBO Max -> badge -> remove
    assertTrue("com.hbo.hbonow" !in st.favorites)
    press(Key.DirectionUp)                                                          // undo
    assertTrue("com.hbo.hbonow" in st.favorites)
    press(Key.Back); rule.mainClock.advanceTimeBy(300)
    assertEquals(null, st.dockEdit)
  }

  private fun ambientShot(name: String, photos: List<Photo>, clock: ClockLook? = null) {
    val st = state(); st.changeAmbientSource(0); st.myPhotos = photos
    clock?.let { st.changeClock(it) }
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    rule.mainClock.advanceTimeBy(300); st.ambient = true
    repeat(8) { rule.mainClock.advanceTimeBy(500); Thread.sleep(400) }
    rule.onRoot().captureRoboImage("$out41/$name.png")
  }
  @Test fun ambientPortraitPair() = ambientShot("A1_ambient_portrait_pair", listOf(analyzed("p832"), analyzed("p838")))
  @Test fun ambientSinglePortrait() = ambientShot("A2_ambient_single_portrait_blur_fill", listOf(analyzed("p1027")),
    ClockLook(ClockStyle.CORNER, ClockFont.INTER, ClockWeight.LIGHT, inAmbient = true))

  /** Original with face (yellow), naive centre crop (red) and face-aware crop (green), next to both results. */
  @Test fun faceAwareCropExample() {
    val p = analyzed("p832"); val m = metas[PhotoMetaStore.keyOf(p)]!!
    val src = BitmapFactory.decodeFile(p.file)!!
    val W = 1920; val H = 1080
    val outB = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(outB)
    c.drawColor(0xFF0E1016.toInt())
    val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 30f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    val sub = android.graphics.Paint(txt).apply { textSize = 22f; typeface = android.graphics.Typeface.DEFAULT; color = 0xB3FFFFFF.toInt() }
    val f = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    // left: original
    val oh = 920f; val ow = oh * src.width / src.height; val ox = 216f; val oy = 130f
    c.drawBitmap(src, null, android.graphics.RectF(ox, oy, ox + ow, oy + oh), f)
    fun box(r: NRect, col: Long, w: Float = 6f) = c.drawRect(ox + r.l * ow, oy + r.t * oh, ox + r.r * ow, oy + r.b * oh,
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = w; color = col.toInt() })
    val ch = (src.width * 9f / 16f) / src.height; val naive = NRect(0f, 0.5f - ch / 2, 1f, 0.5f + ch / 2)
    box(naive, 0xFFFF4D4D); box(m.crop, 0xFF3DDC84); m.faces.forEach { box(it, 0xFFFFD400, 4f) }
    c.drawText("Original 1080×1620", ox, 82f, txt); c.drawText("yellow face · red centre crop · green face-aware", ox, 112f, sub)
    // right: results
    fun result(r: NRect, y: Float, title: String, note: String) {
      val rx = ox + ow + 100f; val rw = 760f; val rh = rw * 9 / 16
      c.drawBitmap(src, android.graphics.Rect((r.l * src.width).toInt(), (r.t * src.height).toInt(), (r.r * src.width).toInt(), (r.b * src.height).toInt()),
        android.graphics.RectF(rx, y, rx + rw, y + rh), f)
      c.drawText(title, rx, y - 38f, txt); c.drawText(note, rx, y - 10f, sub)
    }
    result(naive, 130f, "Centre crop (v4)", "Eyes and forehead cut off")
    result(m.crop, 130f + 428f + 80f, "Face-aware crop (v4.1)", "Whole face kept, eyes on the upper third")
    java.io.File(out41).mkdirs()
    java.io.FileOutputStream("$out41/F1_face_aware_crop_example.png").use { outB.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    assertTrue(m.crop.contains(m.faces[0]))
  }

  // ======================= v4.2: cold-start dock =======================
  private val out42 = "../screens/v42"
  /** Fresh process: prefs (favourites, wallpaper) survive, but nothing PackageManager-derived is in memory yet. */
  private fun coldState(wall: String = "canyon"): LauncherState {
    val warm = state(wall)
    val st = LauncherState(Prefs(ctx))
    st.wallpapers = warm.wallpapers; st.photos = warm.photos; st.weather = warm.weather; st.inputs = warm.inputs
    return st
  }
  /** Renders only the first composed frame (16 ms), i.e. what the TV shows the moment the launcher appears. */
  private fun firstFrame(name: String, st: LauncherState) {
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    rule.mainClock.advanceTimeBy(16)
    rule.onRoot().captureRoboImage("$out42/$name.png")
  }
  /** Before the fix this frame showed an empty dock with only "All apps" (Apps.load had not finished yet). */
  @Test fun coldStartNoAppsYet() {
    val st = coldState()
    assertTrue(st.apps.isEmpty())
    firstFrame("K0_cold_start_first_frame_apps_not_loaded", st)
  }

  /** v4.2: the dock snapshot from the last run is on screen in the very first frame, and the live load changes nothing. */
  @Test fun coldStartCachedDock() {
    val warm = state()
    val favs = warm.favoriteApps
    DockCache.save(ctx, favs)
    DockCache.saveBackdrop(ctx, warm.backdrop!!.asAndroidBitmap())
    val st = coldState()
    st.dockSnapshot = DockCache.load(ctx)
    DockCache.loadBackdrop(ctx)?.let { st.backdrop = it; st.wallpaper = it }
    // Same apps, order, labels, tints and pixel-identical tiles/banners as the live dock.
    assertEquals(favs.map { it.pkg }, st.favoriteApps.map { it.pkg })
    assertEquals(favs.map { it.label }, st.favoriteApps.map { it.label })
    assertEquals(favs.map { it.tint }, st.favoriteApps.map { it.tint })
    favs.zip(st.favoriteApps).forEach { (a, b) ->
      assertEquals(a.tile == null, b.tile == null)
      a.tile?.let { assertTrue("tile ${a.pkg}", it.asAndroidBitmap().sameAs(b.tile!!.asAndroidBitmap())) }
      a.banner?.let { assertTrue("banner ${a.pkg}", it.asAndroidBitmap().sameAs(b.banner!!.asAndroidBitmap())) }
    }
    firstFrame("K1_cold_start_first_frame_cached_dock", st)
    // Every dock app is composed in that first frame (labels live in the focus bubbles).
    favs.forEach { rule.onAllNodesWithText(it.label).fetchSemanticsNodes().let { n -> assertTrue("${it.label} in first frame", n.isNotEmpty()) } }
    // Live PackageManager load arrives (new AppEntry objects): same dock, nothing re-ordered or dropped.
    st.updateApps(sampleApps()); st.wallpaper = warm.wallpaper; st.backdrop = warm.backdrop
    assertEquals(favs.map { it.pkg }, st.favoriteApps.map { it.pkg })
    repeat(3) { rule.mainClock.advanceTimeBy(500); Thread.sleep(150) }
    rule.onRoot().captureRoboImage("$out42/K2_after_live_load_and_wallpaper.png")
  }

  /** v4.2 first-ever start (no snapshot yet): no lone "All apps"; the dock fades in as one unit when apps load. */
  @Test fun coldStartFirstRunFadesInWholeDock() {
    val st = coldState()
    java.io.File(ctx.filesDir, "dock_v1").deleteRecursively()
    st.dockSnapshot = DockCache.load(ctx)
    assertTrue(st.dockSnapshot.isEmpty())
    firstFrame("K3_first_run_first_frame_dock_hidden", st)
    st.updateApps(sampleApps())
    rule.mainClock.advanceTimeBy(130); Thread.sleep(100)
    rule.onRoot().captureRoboImage("$out42/K4_first_run_dock_fading_in.png")
    repeat(2) { rule.mainClock.advanceTimeBy(400); Thread.sleep(100) }
    rule.onRoot().captureRoboImage("$out42/K5_first_run_dock_in.png")
    assertEquals(9, st.favoriteApps.size)
  }

  /** Returning home: a reload that finds nothing changed is a no-op (same list object, no recomposition). */
  @Test fun unchangedReloadIsNoop() {
    val st = state(); val before = st.apps
    st.updateApps(ArrayList(before))
    assertTrue(st.apps === before)
  }

  /** Side by side: what each app ships (on a checkerboard) vs the normalized tile drawn in the dock/grid. */
  @Test fun iconNormalization() {
    val apps = sampleApps().filter { it.pkg in setOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus", "com.hbo.hbonow",
      "com.peacocktv.peacockandroid", "com.plexapp.android", "com.google.android.youtube.tvunplugged", "com.amazon.amazonvideo.livingroom") }
    val type = mapOf("com.google.android.youtube.tv" to "Adaptive", "com.amazon.amazonvideo.livingroom" to "Adaptive", "com.netflix.ninja" to "Legacy · padded",
      "com.hbo.hbonow" to "Legacy · padded", "com.disney.disneyplus" to "Legacy · circle", "com.peacocktv.peacockandroid" to "Legacy · bare logo",
      "com.plexapp.android" to "Banner only", "com.google.android.youtube.tvunplugged" to "Legacy · white tile")
    rule.setContent {
      androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().androidx_bg(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        T("Icon normalization", 30.sp, weight = androidx.compose.ui.text.font.FontWeight.Light)
        androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(26.dp)) {
          apps.forEach { app ->
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
              T(type[app.pkg] ?: "", 11.sp, color = Ink.copy(alpha = 0.7f))
              androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
              Box(Modifier.size(84.dp).checker()) {
                val raw = app.icon ?: app.banner
                if (raw != null) androidx.compose.foundation.Image(raw, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
              }
              T("as shipped", 10.sp, Modifier.padding(top = 4.dp, bottom = 16.dp), color = Ink.copy(alpha = 0.5f))
              UniformIcon(app, 84.dp)
              T(app.label, 12.sp, Modifier.padding(top = 6.dp))
            }
          }
        }
      }
    }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage("$out/08_icon_normalization.png")
  }

  // ======================= GitHub updater (v4.3) =======================
  private val ghRel = GhRelease("v4.3", "4.3", 8, "tv-launcher-v4.3-vc8.apk", "https://example.invalid/x.apk", 1)
  private fun ghShot(name: String, gh: GhUi, screen: Screen = Screen.UPDATE) = shot("../gh/$name", state()) {
    it.versionLabel = "4.2 (build 7)"; it.canInstall = true; it.gh = gh
    it.update = UpdateUi("http://192.168.1.42:8766/731402", "731402"); it.screen = screen
  }
  @Test fun ghChecking() = ghShot("G1_checking", GhUi(GhPhase.CHECKING, "Checking GitHub for updates…"))
  @Test fun ghUpToDate() = ghShot("G2_up_to_date", GhUi(GhPhase.UP_TO_DATE, "You have the latest version (4.2).", ghRel.copy(versionName = "4.2", versionCode = 7)))
  @Test fun ghAvailable() = ghShot("G3_update_available", GhUi(GhPhase.AVAILABLE, "Version 4.3 is available on GitHub.", ghRel))
  @Test fun ghError() = ghShot("G4_error_rate_limited", GhUi(GhPhase.ERROR, "GitHub's limit for update checks was reached. Try again in about 25 min."))
  @Test fun ghSettingsBadge() = ghShot("G5_settings_badge", GhUi(GhPhase.AVAILABLE, "Version 4.3 is available on GitHub.", ghRel), Screen.SETTINGS)
  @Test fun ghDownloading() = shot("../gh/G6_downloading", state()) {
    it.versionLabel = "4.2 (build 7)"; it.canInstall = true; it.gh = GhUi(GhPhase.DOWNLOADING, "Downloading version 4.3…", ghRel)
    it.update = UpdateUi("http://192.168.1.42:8766/731402", "731402", UpdatePhase.RECEIVING, "Downloading 4.3… 48%", 0.48f); it.screen = Screen.UPDATE
  }

  // ======================= Weather location (ZIP) =======================
  private val zipPlace = WeatherPlace("10001", "New York City", "NY", 40.7484, -73.9967)
  @Test fun zipPicker() = shot("../updater/Z1_zip_picker", state()) { it.place = zipPlace; it.screen = Screen.WEATHER_ZIP }
  @Test fun zipPickerUnset() = shot("../updater/Z2_zip_picker_first_time", state()) { it.place = null; it.screen = Screen.WEATHER_ZIP }
  @Test fun zipPickerError() = shot("../updater/Z3_zip_error_unknown", state()) { it.place = zipPlace; it.zipStatus = "99999 isn't a known US ZIP code."; it.screen = Screen.WEATHER_ZIP }
  @Test fun zipPickerBusy() = shot("../updater/Z4_zip_looking_up", state()) { it.place = zipPlace; it.zipStatus = "…"; it.screen = Screen.WEATHER_ZIP }
  @Test fun zipPrompt() = shot("../updater/Z5_home_weather_prompt", state()) { it.place = null; it.weather = null }
  @Test fun zipSettingsRow() {
    val st = state(); st.place = zipPlace
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    st.screen = Screen.SETTINGS
    rule.mainClock.advanceTimeBy(1500); Thread.sleep(300)
    rule.onAllNodesWithText("Weather location")[0].performScrollTo()
    rule.mainClock.advanceTimeBy(1500)
    rule.onRoot().captureRoboImage("../screens/updater/Z6_settings_weather_location.png")
  }
  @Test fun zipCardWithPlace() = shot("../updater/Z7_home_weather_card", state()) { it.place = zipPlace }

  // ======================= Verse of the day (API.Bible) =======================
  // Render fixtures use public-domain World English Bible text labelled WEB: no NIV text is stored in this repo.
  // Notice renders: a made-up holder so the short form is visible ("WEB © 2026 Example Bible Society. All rights reserved.").
  private val fakeNotice = "Render fixture © 2026 by Example Bible Society. Used by permission. All rights reserved worldwide."
  private val webShort = Verse("2026-10-21", "PSA.46.10", "Psalms 46:10", "Be still, and know that I am God. I will be exalted among the nations. I will be exalted in the earth.",
    fakeNotice, "fixture-fums", 0L)
  private val webLong = Verse("2026-10-21", "ISA.41.10", "Isaiah 41:10", "Don't you be afraid, for I am with you. Don't be dismayed, for I am your God. I will strengthen you. Yes, I will help you. Yes, I will uphold you with the right hand of my righteousness.",
    fakeNotice, "fixture-fums", 0L)
  private fun verseState(v: Verse?, key: Boolean, wall: String = "canyon") = state(wall).apply {
    verseKey = key; verseAbbr = "WEB"; verse = v
    changeCards(linkedMapOf(HomeCard.VERSE to CardSlot.LEFT, HomeCard.WEATHER to CardSlot.RIGHT))
  }
  @Test fun verseShort() = shot("../verse/V1_home_verse_card_short_notice_on", verseState(webShort, true))
  @Test fun verseShortNoNotice() = shot("../verse/V1b_home_verse_card_short_notice_off", verseState(webShort, true)) { it.changeVerseNotice(false) }
  /** Both states through the real card: on = reference + short notice; off = reference only, no ©, no api.bible on the card. */
  @Test fun verseNoticeStates() {
    val st = verseState(webShort, true)
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    repeat(3) { rule.mainClock.advanceTimeBy(500); Thread.sleep(100) }
    fun cardText() = rule.onAllNodes(androidx.compose.ui.test.hasText("Psalms", substring = true) or androidx.compose.ui.test.hasText("Psalms\u00A0", substring = true))
      .fetchSemanticsNodes()
      .flatMap { n -> n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text.replace('\u00A0', ' ') } }
    assertTrue(cardText().any { it.contains("Psalms 46:10") && it.contains("WEB © 2026 Example Bible Society. All rights reserved.") })
    assertEquals(0, rule.onAllNodesWithText("api.bible", substring = true).fetchSemanticsNodes().size)
    st.changeVerseNotice(false)
    repeat(3) { rule.mainClock.advanceTimeBy(400); Thread.sleep(100) }
    assertEquals(listOf("Psalms 46:10"), cardText().filter { it.startsWith("Psalms") })
    assertTrue(cardText().none { it.contains("©") })
    assertEquals(0, rule.onAllNodesWithText("api.bible", substring = true).fetchSemanticsNodes().size)
  }
  @Test fun verseLong() = shot("../verse/V2_home_verse_card_long", verseState(webLong, true, "fjord"))
  @Test fun versePrompt() = shot("../verse/V3_home_verse_prompt_no_key", verseState(null, false))
  @Test fun verseKeyScreen() = shot("../verse/V4_add_key_qr_screen", verseState(null, false)) {
    it.keyUi = KeyUi("http://192.168.1.42:8765/482913", "482913"); it.screen = Screen.VERSE_KEY }
  @Test fun verseKeySaved() = shot("../verse/V5_key_saved", verseState(null, true)) {
    it.verseAbbr = "NIV"; it.keyUi = KeyUi("http://192.168.1.42:8765/482913", "482913", KeyPhase.SAVED, "Saved. New International Version (NIV) found; today's verse is on its way."); it.screen = Screen.VERSE_KEY }
  @Test fun verseKeyError() = shot("../verse/V6_key_rejected", verseState(null, false)) {
    it.keyUi = KeyUi("http://192.168.1.42:8765/482913", "482913", KeyPhase.ERROR, "API.Bible didn't accept this key. Copy it again from your API.Bible dashboard."); it.screen = Screen.VERSE_KEY }
  @Test fun verseErrorCard() = shot("../verse/V7_home_verse_invalid_key", verseState(null, true)) { it.verseStatus = VerseStatus.INVALID_KEY }
  @Test fun verseSideRail() = shot("../verse/V8_side_rail_verse_card", verseState(webLong, true, "highlands")) { it.changeLayout(HomeLayout.SIDE_RAIL) }
  @Test fun verseSettings() {
    val st = verseState(webShort, true)
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    st.screen = Screen.SETTINGS
    rule.mainClock.advanceTimeBy(1500); Thread.sleep(300)
    rule.onAllNodesWithText("Remove API.Bible key")[0].performScrollTo()
    rule.mainClock.advanceTimeBy(1500)
    rule.onRoot().captureRoboImage("../screens/verse/V9_settings_verse_section.png")
  }
  /** A refreshed verse with identical text doesn't animate; a new day crossfades (mid-fade frame). */
  @Test fun verseCrossfade() {
    val st = verseState(webShort, true)
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    repeat(3) { rule.mainClock.advanceTimeBy(500); Thread.sleep(200) }
    st.verse = webShort.copy(fetchedAt = 99L, fums = "other")          // same text: nothing to animate
    rule.mainClock.advanceTimeBy(16)
    assertEquals(1, rule.onAllNodesWithText(webShort.text).fetchSemanticsNodes().size)
    st.verse = webLong
    rule.mainClock.advanceTimeBy(380); Thread.sleep(150)
    rule.onRoot().captureRoboImage("../screens/verse/V10_crossfade_mid.png")
    repeat(3) { rule.mainClock.advanceTimeBy(500); Thread.sleep(100) }
    assertEquals(0, rule.onAllNodesWithText(webShort.text).fetchSemanticsNodes().size)
  }

  // ======================= Equal-size side cards (v4.3.2) =======================
  private val webHuge = webLong.copy(text = webLong.text + " Behold, all those who are incensed against you will be disappointed and confounded. Those who strive with you will be like nothing, and shall perish.")
  private fun sides(v: Verse, other: HomeCard, notice: Boolean = true, wall: String = "canyon") = verseState(v, true, wall).apply {
    changeCards(linkedMapOf(HomeCard.VERSE to CardSlot.LEFT, other to CardSlot.RIGHT))
    if (!notice) changeVerseNotice(false)
    if (other == HomeCard.NOTE) note = "Pizza night Friday. Grandma visits Sunday at 2."
    if (other == HomeCard.COUNTDOWN) countdown = Countdown("Christmas", 12, 25)
    if (other == HomeCard.RECENT) recents = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus")
    if (other == HomeCard.PHOTO) photoOfDay = Photos.decode(ctx, Photo(asset = "wallpapers/coast.jpg"), 640)!!.asImageBitmap()
  }
  @Test fun s01() = shot("../sizes/S1_verse_short_notice_on_weather", sides(webShort, HomeCard.WEATHER))
  @Test fun s02() = shot("../sizes/S2_verse_short_notice_off_weather", sides(webShort, HomeCard.WEATHER, false))
  @Test fun s03() = shot("../sizes/S3_verse_long_notice_on_weather", sides(webLong, HomeCard.WEATHER, wall = "fjord"))
  @Test fun s04() = shot("../sizes/S4_verse_long_notice_off_weather", sides(webLong, HomeCard.WEATHER, false, "fjord"))
  @Test fun s05() = shot("../sizes/S5_verse_huge_notice_on_truncates", sides(webHuge, HomeCard.WEATHER, wall = "highlands"))
  @Test fun s06() = shot("../sizes/S6_verse_photo", sides(webShort, HomeCard.PHOTO))
  @Test fun s07() = shot("../sizes/S7_verse_inputs", sides(webLong, HomeCard.INPUTS))
  @Test fun s08() = shot("../sizes/S8_verse_weather3", sides(webShort, HomeCard.WEATHER3))
  @Test fun s09() = shot("../sizes/S9_verse_recent", sides(webShort, HomeCard.RECENT))
  @Test fun s10() = shot("../sizes/S10_verse_date", sides(webShort, HomeCard.DATE, false))
  @Test fun s11() = shot("../sizes/S11_verse_note", sides(webShort, HomeCard.NOTE))
  @Test fun s12() = shot("../sizes/S12_verse_countdown", sides(webShort, HomeCard.COUNTDOWN))
  @Test fun s13() = shot("../sizes/S13_verse_toggles", sides(webShort, HomeCard.TOGGLES))
  @Test fun s14() = shot("../sizes/S14_cinematic_verse_weather", sides(webLong, HomeCard.WEATHER, wall = "coast")) { it.changeLayout(HomeLayout.CINEMATIC) }
  @Test fun s16() = shot("../sizes/S16_two_cards_per_side_natural_heights", sides(webShort, HomeCard.WEATHER, false)) {
    it.changeCards(linkedMapOf(HomeCard.VERSE to CardSlot.LEFT, HomeCard.DATE to CardSlot.LEFT, HomeCard.WEATHER to CardSlot.RIGHT, HomeCard.INPUTS to CardSlot.RIGHT)) }
  @Test fun s15() = shot("../sizes/S15_weather_left_inputs_right", state()) { it.changeCards(linkedMapOf(HomeCard.WEATHER to CardSlot.LEFT, HomeCard.INPUTS to CardSlot.RIGHT)) }

  /** Every card type opposite the verse card (short/long/huge, notice on/off) has exactly the verse card's size, mirrored about the clock. */
  @Test fun sideCardsSameSize() {
    val st = verseState(webShort, true)
    rule.mainClock.autoAdvance = false
    rule.setContent { Box(Modifier.fillMaxSize()) { LauncherRoot(st, actions) } }
    val screenW = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
    fun b(c: HomeCard) = rule.onNode(androidx.compose.ui.test.hasTestTag("card-${c.name}"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val expected = with(rule.density) { androidx.compose.ui.geometry.Size(SideCardWidth.toPx(), SideCardHeight.toPx()) }
    for (v in listOf(webShort, webLong, webHuge)) for (notice in listOf(true, false))
      for (other in HomeCard.values().filter { it != HomeCard.VERSE }) {
        st.verse = v; st.changeVerseNotice(notice)
        st.changeCards(linkedMapOf(HomeCard.VERSE to CardSlot.LEFT, other to CardSlot.RIGHT))
        repeat(3) { rule.mainClock.advanceTimeBy(400) }
        val l = b(HomeCard.VERSE); val r = b(other); val msg = "${v.reference} notice=$notice vs $other: $l / $r"
        assertEquals(msg, expected.width, l.width, 0.5f); assertEquals(msg, expected.height, l.height, 0.5f)
        assertEquals(msg, l.width, r.width, 0.5f); assertEquals(msg, l.height, r.height, 0.5f)
        assertEquals(msg, l.top, r.top, 0.5f); assertEquals(msg, l.left, screenW - r.right, 0.5f)
        // the verse text and reference stay inside the card
        rule.onAllNodes(androidx.compose.ui.test.hasText(v.text.take(20), substring = true), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
          assertTrue("$msg text ${n.boundsInRoot}", n.boundsInRoot.bottom <= l.bottom - 10f) }
        rule.onAllNodes(androidx.compose.ui.test.hasText(v.reference.take(6), substring = true), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
          assertTrue("$msg ref ${n.boundsInRoot}", n.boundsInRoot.bottom <= l.bottom - 10f) }
      }
  }
}

private fun Modifier.androidx_bg() = this.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF1A2236), androidx.compose.ui.graphics.Color(0xFF0B0D14))))
private fun Modifier.checker() = this.drawBehind {
  val s = 8.dp.toPx(); var y = 0f; var r = 0
  while (y < size.height) { var x = 0f; var c = r
    while (x < size.width) { drawRect(if (c % 2 == 0) androidx.compose.ui.graphics.Color(0xFF3A3D48) else androidx.compose.ui.graphics.Color(0xFF2A2C35), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(s, s)); x += s; c++ }
    y += s; r++ }
}
