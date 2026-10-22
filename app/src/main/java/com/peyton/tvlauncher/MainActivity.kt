package com.peyton.tvlauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), Actions {
  internal lateinit var st: LauncherState
  private var lastInput = SystemClock.uptimeMillis()
  private var swallowUp = -1
  private var ambientJob: Job? = null
  private var loadJob: Job? = null


  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    st = LauncherState(Prefs(this))
    // Cold start: last dock + frosted backdrop from app storage, before the first frame (a few small PNGs), so the dock
    // and glass are complete immediately; the live app load below swaps in identical entries without any reflow.
    st.dockSnapshot = DockCache.load(this)
    DockCache.loadBackdrop(this)?.let { st.backdrop = it; st.wallpaper = it }   // sharp wallpaper crossfades in over it
    setContent { LauncherRoot(st, this) }
    // Keep the snapshot in step with the live dock (after loads, edits, installs/uninstalls), off the main thread.
    lifecycleScope.launch {
      androidx.compose.runtime.snapshotFlow { if (st.appsLoaded) st.favoriteApps else null }
        .filterNotNull().distinctUntilChanged()
        .collectLatest { favs -> delay(1500); withContext(Dispatchers.IO) { DockCache.save(this@MainActivity, favs) } }
    }

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        when {
          st.ambient -> stopAmbient()
          st.dockEdit != null -> st.dockEdit = null
          st.menuFor != null -> st.menuFor = null
          st.inputsOpen -> st.inputsOpen = false
          st.allApps -> st.allApps = false
          st.moving != null -> st.moving = null
          st.screen in setOf(Screen.LAYOUT, Screen.CARDS, Screen.CLOCK, Screen.PHONE_UPLOAD, Screen.MY_PHOTOS, Screen.UPDATE) -> st.screen = Screen.SETTINGS
          st.screen != Screen.HOME -> st.screen = Screen.HOME
          else -> st.homeTick++ // a launcher never "exits"; BACK just returns to the top
        }
      }
    })

    // Version label + "updated" notice after a self-update.
    try {
      val pi = packageManager.getPackageInfo(packageName, 0); val code = Updater.versionCodeOf(pi)
      st.versionLabel = "${pi.versionName} (build $code)"
      val prefs = Prefs(this); val last = prefs.lastVersionCode
      if (last in 1 until code) Toast.makeText(this, "TV Launcher updated to ${pi.versionName}", Toast.LENGTH_LONG).show()
      prefs.lastVersionCode = code
    } catch (_: Exception) {}
    Updater.pendingFile(this).delete()

    // Background photo worker finished one photo → refresh thumbnails / ambient plan.
    Ingest.onDone = { runOnUiThread { st.photoMetaTick++; if (!Ingest.busy) lifecycleScope.launch { st.myPhotos = withContext(Dispatchers.IO) { MyPhotos.list(this@MainActivity) } } } }
    loadPhotos()
    // Weather: refresh every 30 min while visible.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
          val stale = st.weather?.let { System.currentTimeMillis() - it.fetchedAt > 25 * 60_000 } ?: true
          if (stale) withContext(Dispatchers.IO) { WeatherApi.fetchJson() }?.let { st.setWeatherJson(it) }
          delay(30 * 60_000L)
        }
      }
    }
    // Quiet GitHub update check: at most once a day, well after the cold start, never installs anything by itself.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        delay(20_000)
        while (true) { quietGitHubCheck(); delay(60 * 60_000L) }
      }
    }
    // Idle → ambient.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        lastInput = SystemClock.uptimeMillis()
        while (true) {
          delay(5_000)
          val m = st.idleMinutes
          if (!st.ambient && m > 0 && SystemClock.uptimeMillis() - lastInput > m * 60_000L) startAmbient()
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    lastInput = SystemClock.uptimeMillis()
    reloadApps()
    st.inputs = loadInputs()
    loadRecents()
    loadPhotoOfDay()
    st.canInstall = Updater.canInstall(this)
    if (st.update?.phase == UpdatePhase.NEEDS_PERMISSION && st.canInstall) installPending()
    if (st.wallpaperChoice < 0 && lastWallDay != System.currentTimeMillis() / 86_400_000L && st.wallpapers.isNotEmpty()) wallpaperChanged()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.action == Updater.ACTION_STATUS) { onInstallStatus(intent); return }
    if (intent.action == Intent.ACTION_MAIN) {
      stopAmbient()
      st.menuFor = null; st.inputsOpen = false; st.moving = null; st.allApps = false
      st.screen = Screen.HOME; st.homeTick++
    }
  }

  private fun reloadApps() {
    loadJob?.cancel()
    loadJob = lifecycleScope.launch {
      val list = withContext(Dispatchers.IO) { Apps.load(this@MainActivity) }
      st.updateApps(list)
    }
  }

  // ------------------------------------------------------------ input / idle

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    lastInput = SystemClock.uptimeMillis()
    if (st.ambient) {
      // Any key exits ambient; volume/mute still pass through to the TV.
      if (event.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) return super.dispatchKeyEvent(event)
      if (event.action == KeyEvent.ACTION_DOWN) { stopAmbient(); swallowUp = event.keyCode }
      return true
    }
    if (event.keyCode == swallowUp) {
      if (event.action == KeyEvent.ACTION_UP) swallowUp = -1
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
    lastInput = SystemClock.uptimeMillis(); return super.dispatchGenericMotionEvent(ev)
  }

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    lastInput = SystemClock.uptimeMillis()
    if (st.ambient) { if (ev.action == MotionEvent.ACTION_UP) stopAmbient(); return true }
    return super.dispatchTouchEvent(ev)
  }

  // ------------------------------------------------------------ Actions

  override fun startAmbient() {
    st.menuFor = null; st.inputsOpen = false; st.moving = null; st.allApps = false
    st.ambient = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    ambientJob?.cancel()
    // Don't keep the TV awake forever: after 4 h let the system's own sleep / screensaver policy take over.
    ambientJob = lifecycleScope.launch { delay(4 * 60 * 60_000L); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }

  private fun stopAmbient() {
    if (!st.ambient) return
    st.ambient = false
    lastInput = SystemClock.uptimeMillis()
    ambientJob?.cancel()
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override fun launch(app: AppEntry) {
    val i = Apps.launchIntent(this, app)
    if (i == null || !start(i)) Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
    else st.noteLaunch(app.pkg)
  }

  /** Recent apps: system usage stats if usage access was granted (adb appops), else what was opened from this launcher. */
  private fun loadRecents() {
    lifecycleScope.launch {
      val list = withContext(Dispatchers.IO) { Recents.query(this@MainActivity) }
      st.usageAccess = list != null
      if (list != null && list.isNotEmpty()) st.recents = (list + st.recents).distinct().take(8)
    }
  }

  private var podDay = -1L
  private fun loadPhotoOfDay() {
    val day = System.currentTimeMillis() / 86_400_000L
    if (day == podDay && st.photoOfDay != null) return
    lifecycleScope.launch {
      val img = withContext(Dispatchers.IO) {
        val mine = MyPhotos.list(this@MainActivity).filter { PhotoMetaStore.get(this@MainActivity, it)?.flag == null }
        val pool = mine.ifEmpty { Photos.builtins(this@MainActivity) }
        if (pool.isEmpty()) null else pool[(day % pool.size).toInt()].let { p ->
          if (p.file != null) MyPhotos.thumbOf(this@MainActivity, p).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
            ?: Photos.decodeCover(this@MainActivity, p, 480, 300)
          else Photos.decodeCover(this@MainActivity, p, 480, 300)
        }?.asImageBitmap()
      }
      if (img != null) { st.photoOfDay = img; podDay = day }
    }
  }

  override fun appInfo(app: AppEntry) {
    start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  override fun systemSettings() {
    if (!start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
      Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show()
  }

  override fun homeSettings() {
    val ok = start(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    if (!ok) Toast.makeText(this, "Google TV hides this screen. Use the adb steps in the README to make TV Launcher the default.", Toast.LENGTH_LONG).show()
  }

  override fun inputs(): List<MenuItem> =
    loadInputs().map { MenuItem(it.label, Icons.Filled.PlayArrow, it.sub) { st.inputsOpen = false; it.action() } } +
      MenuItem("All inputs & Live TV", Icons.Filled.ExitToApp) { st.inputsOpen = false; openInputsPanel() }

  private fun loadInputs(): List<InputUi> {
    val out = ArrayList<InputUi>()
    try {
      val tim = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
      tim?.tvInputList?.filter { it.isPassthroughInput && !it.isHidden(this) }?.forEach { info ->
        val base = info.loadLabel(this).toString()
        val custom = info.loadCustomLabel(this)?.toString()?.takeIf { it.isNotBlank() && it != base }
        val num = Regex("\\d+").find(base)?.value ?: ""
        val short = when (info.type) {
          TvInputInfo.TYPE_HDMI -> "H$num"; TvInputInfo.TYPE_COMPOSITE -> "AV"; TvInputInfo.TYPE_COMPONENT -> "YP"; else -> "IN"
        }
        val sub = if (custom != null) base else if (info.type == TvInputInfo.TYPE_HDMI) "HDMI input" else "External input"
        out += InputUi(short, custom ?: base, sub) {
          if (!start(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) openInputsPanel()
        }
      }
    } catch (_: Exception) {}
    out += InputUi("TV", "Live TV", "Antenna & channels") { openInputsPanel() }
    return out
  }

  private fun openInputsPanel() {
    val tries = listOf(
      Intent("com.android.tv.action.VIEW_INPUTS"),
      packageManager.getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter"),
      packageManager.getLeanbackLaunchIntentForPackage("com.android.tv"),
      packageManager.getLeanbackLaunchIntentForPackage("com.google.android.tv"),
      Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI),
    )
    for (i in tries) if (i != null && start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
    Toast.makeText(this, "Use the Input button on your remote", Toast.LENGTH_SHORT).show()
  }

  private fun loadPhotos() {
    lifecycleScope.launch {
      val (mine, builtin) = withContext(Dispatchers.IO) { MyPhotos.list(this@MainActivity) to Photos.builtins(this@MainActivity) }
      st.myPhotos = mine
      st.photos = builtin
      st.wallpapers = builtin
      wallpaperChanged()   // first: the wallpaper must not wait for the photo backfill scan
      withContext(Dispatchers.IO) { Ingest.backfill(this@MainActivity, mine + builtin) }
    }
  }

  // ------------------------------------------------------------ photos from phone (LAN server)

  private var server: PhotoServer? = null

  private var photoStarting = false
  private var photoGen = 0

  override fun startPhotoServer() {
    if (server != null) { linger?.cancel(); st.upload = st.upload?.copy(background = false); return }
    if (photoStarting) return
    photoStarting = true
    val gen = photoGen
    lifecycleScope.launch(kotlinx.coroutines.NonCancellable) {
      val srv = withContext(Dispatchers.IO) {
        PhotoServer.start(this@MainActivity) { batch, total ->
          // Called on a server thread after each saved photo (several may run in parallel).
          runOnUiThread {
            st.upload = st.upload?.let { u ->
              val same = batch != null && batch == curBatch
              if (!same) curBatch = batch
              u.copy(received = u.received + 1, batchDone = if (same) u.batchDone + 1 else 1, batchTotal = total)
            }
            refreshMine()
          }
        }
      }
      photoStarting = false
      if (gen != photoGen || st.screen != Screen.PHONE_UPLOAD) { withContext(Dispatchers.IO) { srv?.stop() }; return@launch }   // user already left
      server = srv
      val ip = withContext(Dispatchers.IO) { PhotoServer.lanAddress() }
      st.upload = when {
        srv == null -> UploadUi(null, "", error = "Couldn't start sharing on this TV (ports 8765-8775 busy).")
        ip == null -> { srv.stop(); server = null; UploadUi(null, "", error = "The TV isn't connected to a network.") }
        else -> UploadUi("http://$ip:${srv.listeningPort}/${srv.pin}", srv.pin)
      }
    }
  }

  private var curBatch: String? = null
  private var refreshJob: Job? = null
  /** Coalesces list refreshes while a burst of photos arrives. */
  private fun refreshMine() {
    if (refreshJob?.isActive == true) return
    refreshJob = lifecycleScope.launch { delay(400); st.myPhotos = withContext(Dispatchers.IO) { MyPhotos.list(this@MainActivity) } }
  }

  private var linger: Job? = null
  /**
   * Leaving "Add photos" mid-batch: keep accepting that batch (same PIN, same LAN rules) until it has been idle for
   * 12 s, with a small "Receiving photos" pill on the home screen. Never while the launcher is in the background.
   */
  override fun stopPhotoServer() {
    val srv = server
    if (srv != null && (srv.active.get() > 0 || System.currentTimeMillis() - srv.lastActivity < 8_000) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
      st.upload = st.upload?.copy(background = true)
      linger?.cancel()
      linger = lifecycleScope.launch {
        while (srv.active.get() > 0 || System.currentTimeMillis() - srv.lastActivity < 12_000) delay(1000)
        if (server === srv && st.screen != Screen.PHONE_UPLOAD) stopPhotoServerNow()
      }
      return
    }
    stopPhotoServerNow()
  }

  private fun stopPhotoServerNow() {
    linger?.cancel(); photoGen++
    server?.stop(); server = null; st.upload = null
  }

  override fun deletePhoto(p: Photo) {
    lifecycleScope.launch { st.myPhotos = withContext(Dispatchers.IO) { MyPhotos.delete(this@MainActivity, p); MyPhotos.list(this@MainActivity) } }
  }

  override fun deleteAllPhotos() {
    lifecycleScope.launch { st.myPhotos = withContext(Dispatchers.IO) { MyPhotos.list(this@MainActivity).forEach { MyPhotos.delete(this@MainActivity, it) }; MyPhotos.list(this@MainActivity) } }
  }

  override fun loadThumb(p: Photo): ImageBitmap? = try {
    val t = MyPhotos.thumbOf(this, p)
    (if (t.exists()) android.graphics.BitmapFactory.decodeFile(t.path) else Photos.decodeCover(this, p, 320, 200))?.also { it.prepareToDraw() }?.asImageBitmap()
  } catch (_: Throwable) { null }

  override fun photoMeta(p: Photo): PhotoMeta? = PhotoMetaStore.get(this, p)
  override fun loadCover(p: Photo, w: Int, h: Int): android.graphics.Bitmap? = Photos.decodeCover(this, p, w, h)

  override fun onStart() {
    super.onStart()
    if (st.screen == Screen.PHONE_UPLOAD) startPhotoServer()
  }

  override fun onStop() {
    stopPhotoServerNow()   // never leave the server running in the background
    // Keep the update server only while the system install prompt is covering us, so the phone sees the result.
    if (st.update?.phase != UpdatePhase.CONFIRM && st.update?.phase != UpdatePhase.INSTALLING) stopUpdateServer()
    super.onStop()
  }

  // ------------------------------------------------------------ self-update from phone

  private var updateServer: PhotoServer? = null
  @Volatile private var updSnap = UpdateUi()

  private fun setUpdate(f: (UpdateUi) -> UpdateUi) {
    val run = { val n = f(st.update ?: updSnap); st.update = n; updSnap = n }
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run() else runOnUiThread(run)
  }

  private val hooks = object : UpdateHooks {
    override fun onProgress(read: Long, total: Long) =
      setUpdate { it.copy(phase = UpdatePhase.RECEIVING, message = "Receiving update… ${read * 100 / total}%", progress = read / total.toFloat()) }

    override fun onApk(file: java.io.File): Pair<Boolean, String> {
      val why = verifyAndStage(file) { meta, cur -> Updater.check(meta, cur) }
      return if (why != null) false to why
      else true to "Version ${updSnap.pending?.versionName ?: ""} checks out (same app, same key, newer). Look at the TV to confirm the install."
    }

    override fun statusJson(): String {
      val u = updSnap
      return "{\"phase\":\"${u.phase}\",\"message\":${PhotoServer.q(u.message)},\"progress\":${u.progress ?: "null"}}"
    }
  }

  /**
   * Shared by the phone/QR path and the GitHub path: inspect the APK, run [rules] (null = OK), then stage it as the
   * pending update and hand it to PackageInstaller (or wait for the "install unknown apps" permission). Blocking; IO thread.
   */
  private fun verifyAndStage(file: java.io.File, rules: (ApkMeta?, ApkMeta) -> String?): String? {
    setUpdate { it.copy(phase = UpdatePhase.VERIFYING, message = "Checking the file…", progress = null) }
    val meta = Updater.inspect(this, file)
    val cur = Updater.installed(this)
    rules(meta, cur)?.let { why ->
      file.delete(); setUpdate { it.copy(phase = UpdatePhase.ERROR, message = why, progress = null, pending = null) }
      return why
    }
    val dest = Updater.pendingFile(this); dest.delete()
    if (!file.renameTo(dest)) { file.copyTo(dest, true); file.delete() }
    val v = meta!!.versionName ?: meta.versionCode.toString()
    updSnap = updSnap.copy(pending = meta)
    runOnUiThread {
      if (Updater.canInstall(this)) { setUpdate { it.copy(pending = meta) }; installPending() }
      else setUpdate { it.copy(phase = UpdatePhase.NEEDS_PERMISSION, pending = meta,
        message = "Version $v is ready. Allow installs for TV Launcher on the TV, then press Install.") }
    }
    return null
  }

  // ------------------------------------------------------------ self-update from GitHub Releases

  private var ghJob: Job? = null
  private fun installedVc() = runCatching { Updater.installed(this).versionCode }.getOrDefault(Long.MAX_VALUE)

  /** Background check (see onCreate): quiet, at most once a day; only ever shows the "update available" badge. */
  private suspend fun quietGitHubCheck() {
    val prefs = Prefs(this)
    if (st.gh.phase in setOf(GhPhase.AVAILABLE, GhPhase.CHECKING, GhPhase.DOWNLOADING)) return
    if (System.currentTimeMillis() - prefs.ghLastCheck < 24 * 60 * 60_000L) return
    val vc = installedVc()
    val r = withContext(Dispatchers.IO) { GitHubReleases.fetchLatest(Updater.GH_REPO, Updater.GH_ASSET_PREFIX, vc) }
    // An available update isn't recorded, so a restart re-checks once and the badge comes back.
    if (r !is GhCheck.Available) prefs.ghLastCheck = System.currentTimeMillis()
    if (st.gh.phase == GhPhase.CHECKING || st.gh.phase == GhPhase.DOWNLOADING) return   // a manual check won the race
    if (r !is GhCheck.Failed) st.gh = GhUi.of(r)   // errors stay silent in the background
  }

  override fun checkGitHub() {
    if (st.gh.phase == GhPhase.CHECKING || st.gh.phase == GhPhase.DOWNLOADING) return
    st.gh = GhUi(GhPhase.CHECKING, "Checking GitHub for updates…", st.gh.release)
    ghJob = lifecycleScope.launch {
      val vc = installedVc()
      val r = withContext(Dispatchers.IO) { GitHubReleases.fetchLatest(Updater.GH_REPO, Updater.GH_ASSET_PREFIX, vc) }
      if (r !is GhCheck.Available) Prefs(this@MainActivity).ghLastCheck = System.currentTimeMillis()
      st.gh = GhUi.of(r)
    }
  }

  override fun installFromGitHub() {
    val rel = st.gh.release?.takeIf { st.gh.phase == GhPhase.AVAILABLE } ?: return checkGitHub()
    st.gh = GhUi(GhPhase.DOWNLOADING, "Downloading version ${rel.versionName}…", rel)
    setUpdate { it.copy(phase = UpdatePhase.RECEIVING, message = "Downloading ${rel.versionName} from GitHub…", progress = 0f, pending = null) }
    ghJob = lifecycleScope.launch {
      val f = Updater.downloadFile(this@MainActivity)
      val why = withContext(Dispatchers.IO) {
        try {
          GitHubReleases.download(rel, f) { read, total ->
            if (total > 0) setUpdate { it.copy(phase = UpdatePhase.RECEIVING, message = "Downloading ${rel.versionName}… ${read * 100 / total}%", progress = read / total.toFloat()) }
          }
          verifyAndStage(f) { meta, cur -> Updater.checkGitHub(meta, cur, rel) }
        } catch (e: java.io.IOException) {
          f.delete(); (e.message ?: "Download failed.").also { m -> setUpdate { it.copy(phase = UpdatePhase.ERROR, message = m, progress = null) } }
        }
      }
      st.gh = if (why == null) GhUi(GhPhase.AVAILABLE, "Version ${rel.versionName} downloaded and verified.", rel) else GhUi(GhPhase.ERROR, why, rel)
    }
  }

  private var updateStarting = false
  private var updateGen = 0

  override fun startUpdateServer() {
    if (updateServer != null || updateStarting) return   // screen effect + explicit calls must not open two servers
    updateStarting = true
    val gen = updateGen
    st.canInstall = Updater.canInstall(this)
    lifecycleScope.launch(kotlinx.coroutines.NonCancellable) {
      val srv = withContext(Dispatchers.IO) { PhotoServer.startUpdate(this@MainActivity, hooks) }
      updateStarting = false
      if (gen != updateGen || st.screen != Screen.UPDATE) { withContext(Dispatchers.IO) { srv?.stop() }; return@launch }
      updateServer = srv
      val ip = withContext(Dispatchers.IO) { PhotoServer.lanAddress() }
      val keep = st.update?.takeIf { it.phase == UpdatePhase.NEEDS_PERMISSION || it.phase == UpdatePhase.READY || it.phase == UpdatePhase.ERROR }
      setUpdate {
        val base = keep ?: UpdateUi()
        when {
          srv == null -> base.copy(url = null, serverError = "Couldn't start sharing on this TV (ports 8765-8775 busy).")
          ip == null -> { srv.stop(); updateServer = null; base.copy(url = null, serverError = "The TV isn't connected to a network.") }
          else -> base.copy(url = "http://$ip:${srv.listeningPort}/${srv.pin}", pin = srv.pin, serverError = null)
        }
      }
    }
  }

  override fun stopUpdateServer() {
    updateGen++
    updateServer?.stop(); updateServer = null
    if (st.screen != Screen.UPDATE) { st.update = null; updSnap = UpdateUi() }
  }

  override fun openInstallPermission() {
    for (i in Updater.permissionIntents(this)) if (start(i)) return
    Toast.makeText(this, "Open Settings → Apps → Security & restrictions → Unknown sources, and allow TV Launcher", Toast.LENGTH_LONG).show()
  }

  override fun installPending() {
    val f = Updater.pendingFile(this)
    if (!f.exists()) { setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "No update file is waiting. Send the .apk again.", pending = null) }; return }
    if (!Updater.canInstall(this)) { setUpdate { it.copy(phase = UpdatePhase.NEEDS_PERMISSION, message = "Allow installs for TV Launcher first, then press Install.") }; return }
    setUpdate { it.copy(phase = UpdatePhase.INSTALLING, message = "Preparing the install…", progress = null) }
    lifecycleScope.launch {
      val err = try { Updater.install(this@MainActivity, f); null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.w("TvLauncher", "install failed", e); e.message ?: e.javaClass.simpleName }
      if (err != null) setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "Couldn't start the install: $err") }
    }
  }

  private fun onInstallStatus(intent: Intent) {
    val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
    val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
    when (status) {
      android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        setUpdate { it.copy(phase = UpdatePhase.CONFIRM, message = "Confirm the update on the TV: choose Update / Install.") }
        if (confirm == null || !start(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "The TV didn't show the install prompt.") }
      }
      android.content.pm.PackageInstaller.STATUS_SUCCESS -> { Updater.pendingFile(this).delete(); st.gh = GhUi(); setUpdate { it.copy(phase = UpdatePhase.DONE, message = "Updated! The launcher restarts on the new version.", pending = null) } }
      android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "Install was cancelled on the TV. Press Install to try again.") }
      android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "Not enough storage on the TV for the update.") }
      android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
        setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "The TV refused the update (${msg ?: "conflict"}).") }
      else -> setUpdate { it.copy(phase = UpdatePhase.ERROR, message = "Install failed: ${msg ?: "status $status"}") }
    }
  }

  private var wallJob: Job? = null
  private var lastWallDay = -1L
  override fun wallpaperChanged() {
    wallJob?.cancel()
    wallJob = lifecycleScope.launch {
      val wp = st.wallpapers; if (wp.isEmpty()) return@launch
      lastWallDay = System.currentTimeMillis() / 86_400_000L
      val pick = if (st.wallpaperChoice in wp.indices) wp[st.wallpaperChoice] else wp[((System.currentTimeMillis() / 86_400_000L) % wp.size).toInt()]
      // Decode at screen size + blur entirely off the main thread; HomeV2 crossfades when these change.
      val (full, blur) = withContext(Dispatchers.Default) {
        val f = Photos.decode(this@MainActivity, pick, 1920) ?: return@withContext null to null
        val b = Photos.frosted(f).also { it.prepareToDraw() }
        val key = pick.asset ?: pick.file
        val sp = getSharedPreferences("launcher", MODE_PRIVATE)
        if (sp.getString("backdropKey", null) != key) { DockCache.saveBackdrop(this@MainActivity, b); sp.edit().putString("backdropKey", key).apply() }
        f to b
      }
      if (full == null || blur == null) return@launch
      st.backdrop = blur.asImageBitmap(); st.wallpaper = full.asImageBitmap()
    }
  }


  private fun start(i: Intent): Boolean = try { startActivity(i); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
