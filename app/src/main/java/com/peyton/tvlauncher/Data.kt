package com.peyton.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One launchable app on the TV. */
data class AppEntry(
  val pkg: String,
  val label: String,
  val component: ComponentName?,
  val banner: ImageBitmap?,
  val icon: ImageBitmap?,
  val tint: Color,
  val leanback: Boolean,
  /** Uniform, fully-filled square tile (adaptive layers / trimmed legacy icon / banner), drawn in the squircle mask. */
  val tile: ImageBitmap? = null,
)

object Apps {
  private class Memo(val sig: String, val entry: AppEntry)
  /** Entries from the previous load, reused while the package is unchanged (same activity, label, install/update time). */
  private val memo = HashMap<String, Memo>()

  /**
   * Lists leanback (TV) apps first, then regular phone-style launcher apps that have no TV entry.
   * Unchanged packages return the very same [AppEntry] (same bitmaps), so a reload on every return home costs a
   * PackageManager query, not a re-decode of every icon, and an unchanged list compares equal (no recomposition).
   */
  @Synchronized fun load(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val out = LinkedHashMap<String, AppEntry>()
    // Decode at the exact on-screen pixel sizes: grid banner 146dp wide, focused dock icon ≈ 56dp × 1.22.
    val dens = ctx.resources.displayMetrics.density
    val bw = (146 * dens).toInt().coerceIn(160, 480)
    tilePx = (69 * dens).toInt().coerceIn(96, 256)
    fun query(cat: String) = try {
      pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
    } catch (_: Exception) { emptyList() }
    for ((cat, lb) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
      for (ri in query(cat)) {
        val ai = ri.activityInfo ?: continue
        val pkg = ai.packageName
        if (pkg == ctx.packageName || out.containsKey(pkg)) continue
        val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
        val updated = try { pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val sig = "${ai.name}|$lb|$label|$updated|$bw|$tilePx"
        val known = memo[pkg]
        if (known != null && known.sig == sig) { out[pkg] = known.entry; continue }
        val bannerBmp = try { (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))?.toBitmap(bw, bw * 9 / 16) } catch (_: Exception) { null }
        val d = try { ri.loadIcon(pm) } catch (_: Exception) { null }
        val adaptive = d is AdaptiveIconDrawable
        val iconBmp = try { if (d is AdaptiveIconDrawable) adaptiveFull(d, tilePx) else d?.toBitmap(tilePx, tilePx) } catch (_: Exception) { null }
        out[pkg] = entry(pkg, label, ComponentName(pkg, ai.name), bannerBmp, iconBmp, adaptive, lb).also { memo[pkg] = Memo(sig, it) }
      }
    }
    memo.keys.retainAll(out.keys)
    return out.values.sortedBy { it.label.lowercase() }
  }

  @Volatile var tilePx = 192

  /** Builds an entry with GPU-ready bitmaps and a normalized square tile. Call off the main thread. */
  fun entry(pkg: String, label: String, component: ComponentName?, banner: Bitmap?, icon: Bitmap?, adaptive: Boolean, leanback: Boolean): AppEntry {
    val tile = try { Tiles.make(icon, adaptive, banner, icon?.width ?: tilePx) } catch (_: Exception) { null }
    banner?.prepareToDraw(); icon?.prepareToDraw(); tile?.prepareToDraw()
    return AppEntry(pkg, label, component, banner?.asImageBitmap(), icon?.asImageBitmap(), tintOf(tile ?: icon ?: banner), leanback, tile?.asImageBitmap())
  }

  /** Renders an adaptive icon's layers edge-to-edge (no system mask) so the squircle tile is the icon. */
  fun adaptiveFull(d: AdaptiveIconDrawable, px: Int): Bitmap {
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
    val e = px / 4 // layers are 108dp with the visible 72dp viewport in the middle → extend 1/4 per side
    for (layer in listOfNotNull(d.background, d.foreground)) { layer.setBounds(-e, -e, px + e, px + e); layer.draw(c) }
    return b
  }

  fun tintOf(b: Bitmap?): Color {
    if (b == null) return Color(0xFF3A3F66)
    val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(px, hsv)
    hsv[1] = if (hsv[1] < 0.15f) hsv[1] * 0.5f else (hsv[1] * 1.1f).coerceIn(0.25f, 0.85f)   // keep neutral icons neutral
    hsv[2] = hsv[2].coerceIn(0.35f, 0.62f)
    return Color(android.graphics.Color.HSVToColor(hsv))
  }

  fun launchIntent(ctx: Context, app: AppEntry): Intent? {
    val pm = ctx.packageManager
    val i = (if (app.leanback) pm.getLeanbackLaunchIntentForPackage(app.pkg) else null)
      ?: pm.getLaunchIntentForPackage(app.pkg)
      ?: pm.getLeanbackLaunchIntentForPackage(app.pkg)
      ?: app.component?.let { Intent(Intent.ACTION_MAIN).setComponent(it) }
    return i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
  }

  /** Default favorites seeded on first run, in this order, if installed. */
  val SEED = listOf(
    "com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus", "com.hulu.livingroomplus",
    "com.amazon.amazonvideo.livingroom", "com.wbd.stream", "com.hbo.hbonow", "com.peacocktv.peacockandroid",
    "com.apple.atve.androidtv.appletv", "com.plexapp.android", "com.spotify.tv.android", "com.google.android.youtube.tvunplugged",
  )
}

class Prefs(ctx: Context) {
  internal val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
  var favorites: List<String>
    get() = sp.getString("fav", "")!!.split(',').filter { it.isNotBlank() }
    set(v) = sp.edit().putString("fav", v.joinToString(",")).apply()
  var hidden: Set<String>
    get() = sp.getStringSet("hidden", emptySet())!!.toSet()
    set(v) = sp.edit().putStringSet("hidden", v).apply()
  var seeded: Boolean
    get() = sp.getBoolean("seeded", false)
    set(v) = sp.edit().putBoolean("seeded", v).apply()
  var idleMinutes: Int
    get() = sp.getInt("idle", 5)
    set(v) = sp.edit().putInt("idle", v).apply()
  var use24h: Boolean
    get() = sp.getBoolean("24h", false)
    set(v) = sp.edit().putBoolean("24h", v).apply()
  var celsius: Boolean
    get() = sp.getBoolean("celsius", false)
    set(v) = sp.edit().putBoolean("celsius", v).apply()
  var showWeather: Boolean
    get() = sp.getBoolean("weather", true)
    set(v) = sp.edit().putBoolean("weather", v).apply()
  var wallpaper: Int
    get() = sp.getInt("wallpaper", -1)
    set(v) = sp.edit().putInt("wallpaper", v).apply()
  var ambientSource: Int
    get() = sp.getInt("ambientSource", 0)
    set(v) = sp.edit().putInt("ambientSource", v).apply()
  var lastVersionCode: Long
    get() = sp.getLong("lastVersionCode", 0L)
    set(v) = sp.edit().putLong("lastVersionCode", v).apply()
  var weatherJson: String?
    get() = sp.getString("weatherJson", null)
    set(v) = sp.edit().putString("weatherJson", v).apply()
}

// ---------------------------------------------------------------- Weather (Open-Meteo, no key)

data class Hour(val label: String, val tempF: Double, val code: Int, val isDay: Boolean)
data class Day(val label: String, val code: Int, val hiF: Double, val loF: Double)
data class Weather(val tempF: Double, val code: Int, val hiF: Double, val loF: Double, val isDay: Boolean, val fetchedAt: Long, val hours: List<Hour> = emptyList(), val days: List<Day> = emptyList())

enum class Sky { SUN, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

object WeatherApi {
  /** Location comes from BuildConfig (tvlauncher.weather.* / TVL_WEATHER_*), never hardcoded in the repo. */
  val PLACE: String = BuildConfig.WEATHER_PLACE
  private val URL_STR = "https://api.open-meteo.com/v1/forecast?latitude=${BuildConfig.WEATHER_LAT}&longitude=${BuildConfig.WEATHER_LON}" +
    "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
    "&hourly=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit&timezone=${java.net.URLEncoder.encode(BuildConfig.WEATHER_TZ, "UTF-8")}&forecast_days=4"

  fun fetchJson(): String? = try {
    val c = URL(URL_STR).openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "TvLauncher/1.0")
    try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
  } catch (_: Exception) { null }

  fun parse(json: String?): Weather? = try {
    val o = JSONObject(json!!); val cur = o.getJSONObject("current"); val d = o.getJSONObject("daily")
    val hours = ArrayList<Hour>()
    o.optJSONObject("hourly")?.let { h ->
      val t = h.getJSONArray("time"); val now = cur.optString("time")
      var i = 0; while (i < t.length() && t.getString(i) <= now) i++
      while (i < t.length() && hours.size < 5) {
        val hh = t.getString(i).substringAfter('T').take(2).toIntOrNull() ?: 0
        val lab = when { hh == 0 -> "12 AM"; hh < 12 -> "$hh AM"; hh == 12 -> "12 PM"; else -> "${hh - 12} PM" }
        hours += Hour(lab, h.getJSONArray("temperature_2m").getDouble(i), h.getJSONArray("weather_code").getInt(i), h.getJSONArray("is_day").optInt(i, 1) == 1)
        i++
      }
    }
    val days = ArrayList<Day>()
    val dt = d.optJSONArray("time"); val dc = d.optJSONArray("weather_code")
    if (dt != null) for (i in 0 until minOf(dt.length(), 4)) {
      val lab = if (i == 0) "Today" else try { java.time.LocalDate.parse(dt.getString(i)).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) } catch (_: Exception) { "" }
      days += Day(lab, dc?.optInt(i, 3) ?: 3, d.getJSONArray("temperature_2m_max").getDouble(i), d.getJSONArray("temperature_2m_min").getDouble(i))
    }
    Weather(cur.getDouble("temperature_2m"), cur.getInt("weather_code"),
      d.getJSONArray("temperature_2m_max").getDouble(0), d.getJSONArray("temperature_2m_min").getDouble(0),
      cur.optInt("is_day", 1) == 1, System.currentTimeMillis(), hours, days)
  } catch (_: Exception) { null }

  fun describe(code: Int) = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"
    45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
    in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorms"; else -> "—"
  }

  fun sky(code: Int) = when (code) {
    0, 1 -> Sky.SUN; 2 -> Sky.PARTLY; 3 -> Sky.CLOUD; 45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN; in 71..77, 85, 86 -> Sky.SNOW; in 95..99 -> Sky.STORM; else -> Sky.CLOUD
  }

  fun fmt(f: Double, celsius: Boolean) = if (celsius) "${Math.round((f - 32) * 5 / 9)}°" else "${Math.round(f)}°"
}

// ---------------------------------------------------------------- Photos

/** A built-in asset or a photo uploaded from Peyton's phone (app-private file). */
data class Photo(val asset: String? = null, val file: String? = null)

object Photos {
  /** Bundled photographic wallpapers (Unsplash-licensed via Picsum); also the ambient fallback. */
  fun builtins(ctx: Context): List<Photo> =
    (ctx.assets.list("wallpapers") ?: emptyArray()).sorted().map { Photo(asset = "wallpapers/$it") }

  fun nameOf(p: Photo) = p.asset?.substringAfterLast('/')?.substringBeforeLast('.')?.replaceFirstChar { it.uppercase() } ?: "Photo"

  /** Ambient source: 0 = My photos (falls back to built-in when empty), 1 = Built-in, 2 = Both. */
  fun forAmbient(source: Int, mine: List<Photo>, builtin: List<Photo>) = when (source) {
    1 -> builtin; 2 -> mine + builtin; else -> mine.ifEmpty { builtin }
  }

  /**
   * Decodes so the result still covers [tw]x[th] (no bigger than needed), uploads to the GPU early. Blocking.
   */
  fun decodeCover(ctx: Context, p: Photo, tw: Int, th: Int): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (p.asset != null) ctx.assets.open(p.asset).use { BitmapFactory.decodeStream(it, null, o) } else BitmapFactory.decodeFile(p.file, o)
    var s = 1; while (o.outWidth / (s * 2) >= tw && o.outHeight / (s * 2) >= th) s *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = s }
    var b = (if (p.asset != null) ctx.assets.open(p.asset).use { BitmapFactory.decodeStream(it, null, opts) } else BitmapFactory.decodeFile(p.file, opts))
    if (b != null) {
      val sc = maxOf(tw / b.width.toFloat(), th / b.height.toFloat())
      if (sc < 0.8f) { val n = Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true); if (n !== b) b.recycle(); b = n }
      b.prepareToDraw()
    }
    b
  } catch (_: Throwable) { null }

  /** Decodes at (about) screen size and uploads to the GPU early. Blocking: call off the main thread. */
  fun decode(ctx: Context, p: Photo, maxW: Int = 1920): Bitmap? = try {
    val b = if (p.asset != null) ctx.assets.open(p.asset).use { BitmapFactory.decodeStream(it) }
    else {
      val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(p.file, o)
      var s = 1; while (o.outWidth / (s * 2) >= maxW) s *= 2
      BitmapFactory.decodeFile(p.file, BitmapFactory.Options().apply { inSampleSize = s })
    }
    b?.prepareToDraw(); b
  } catch (_: Throwable) { null }

  /** Soft "frosted glass" version of a photo for the home background (fast 3-pass box blur on a small copy). */
  fun frosted(src: Bitmap, w: Int = 384, h: Int = 216, r: Int = 9, saturation: Float = 1.35f): Bitmap {
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(b).drawBitmap(small, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
      colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(saturation) })
    })
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, r) }
    b.setPixels(px, 0, w, 0, 0, w, h)
    return b
  }

  /** Blurred 16:9 centre crop (320x180) used behind portraits / contained photos. */
  fun blurFill(src: Bitmap): Bitmap {
    val ia = src.width / src.height.toFloat(); val a = 16f / 9f
    val (cw, ch) = if (ia > a) (src.height * a).toInt() to src.height else src.width to (src.width / a).toInt()
    val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
    val w = 320; val h = 180
    val b = Bitmap.createScaledBitmap(crop, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
    if (crop !== src) crop.recycle()
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, 8) }
    b.setPixels(px, 0, w, 0, 0, w, h); b.prepareToDraw()
    return b
  }

  private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int) {
    val t = IntArray(p.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, stride: Int, step: Int) {
      for (l in 0 until lines) {
        var rs = 0; var gs = 0; var bs = 0
        val base = l * stride
        for (i in -r..r) { val c = src[base + i.coerceIn(0, len - 1) * step]; rs += c shr 16 and 255; gs += c shr 8 and 255; bs += c and 255 }
        val n = 2 * r + 1
        for (i in 0 until len) {
          dst[base + i * step] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
          val a = src[base + (i + r + 1).coerceAtMost(len - 1) * step]; val o = src[base + (i - r).coerceAtLeast(0) * step]
          rs += (a shr 16 and 255) - (o shr 16 and 255); gs += (a shr 8 and 255) - (o shr 8 and 255); bs += (a and 255) - (o and 255)
        }
      }
    }
    pass(p, t, w, h, w, 1)   // horizontal
    pass(t, p, h, w, 1, w)   // vertical
  }
}

// ---------------------------------------------------------------- UI state

enum class Screen { HOME, SETTINGS, PHONE_UPLOAD, MY_PHOTOS, UPDATE, LAYOUT, CARDS, CLOCK }

/** Live info for the "Add photos from phone" screen. */
data class UploadUi(val url: String?, val pin: String, val received: Int = 0, val error: String? = null,
  /** Current phone batch: how many of how many have arrived (phone sends X-Batch: id:total). */
  val batchDone: Int = 0, val batchTotal: Int = 0, val background: Boolean = false)

const val MAX_DOCK = 24

class LauncherState(private val prefs: Prefs) {
  var apps by mutableStateOf(listOf<AppEntry>())
  /** True once [apps] holds a live PackageManager load (until then the dock is drawn from [dockSnapshot]). */
  var appsLoaded by mutableStateOf(false)
  /** Dock as it looked last time (see [DockCache]); read synchronously before the first frame of a cold start. */
  var dockSnapshot by mutableStateOf(listOf<AppEntry>())
  var favorites by mutableStateOf(prefs.favorites)
  var hidden by mutableStateOf(prefs.hidden)
  var screen by mutableStateOf(Screen.HOME)
  var ambient by mutableStateOf(false)
  var weather by mutableStateOf(WeatherApi.parse(prefs.weatherJson))
  var photos by mutableStateOf(listOf<Photo>())
  var myPhotos by mutableStateOf(listOf<Photo>())
  var ambientSource by mutableIntStateOf(prefs.ambientSource)
  var upload by mutableStateOf<UploadUi?>(null)
  var update by mutableStateOf<UpdateUi?>(null)
  var canInstall by mutableStateOf(false)
  var versionLabel by mutableStateOf("")
  /** Pre-blurred copy of the wallpaper; glass surfaces sample it at their screen position. */
  var backdrop by mutableStateOf<ImageBitmap?>(null)
  var wallpaper by mutableStateOf<ImageBitmap?>(null)
  var wallpapers by mutableStateOf(listOf<Photo>())
  var wallpaperChoice by mutableIntStateOf(prefs.wallpaper)
  var allApps by mutableStateOf(false)
  var inputs by mutableStateOf(listOf<InputUi>())
  var menuFor by mutableStateOf<AppEntry?>(null)
  var inputsOpen by mutableStateOf(false)
  var moving by mutableStateOf<String?>(null)
  var homeTick by mutableIntStateOf(0)
  var idleMinutes by mutableIntStateOf(prefs.idleMinutes)
  var use24h by mutableStateOf(prefs.use24h)
  var celsius by mutableStateOf(prefs.celsius)
  var showWeather by mutableStateOf(prefs.showWeather)

  // ---- v4.1 customization (see Customize.kt)
  var layout by mutableStateOf(Look.layout(prefs))
  var cards by mutableStateOf(Look.cards(prefs))
  var clock by mutableStateOf(Look.clock(prefs))
  var countdown by mutableStateOf(Look.countdown(prefs))
  var note by mutableStateOf(prefs.sp.getString("note", "") ?: "")
  var autoEnhance by mutableStateOf(prefs.sp.getBoolean("enhance", false))
  var recents by mutableStateOf(prefs.sp.getString("recents", "")!!.split(',').filter { it.isNotBlank() })
  var usageAccess by mutableStateOf(false)
  var photoOfDay by mutableStateOf<ImageBitmap?>(null)
  var dockEdit by mutableStateOf<DockEditor?>(null)
  /** Bumped when the background photo worker finishes a photo (meta/thumb ready). */
  var photoMetaTick by mutableIntStateOf(0)

  fun changeLayout(l: HomeLayout) { layout = l; prefs.sp.edit().putString("layout", l.name).apply() }
  fun changeCards(m: Map<HomeCard, CardSlot>) { cards = m; Look.saveCards(prefs, m) }
  fun changeClock(c: ClockLook) { clock = c; Look.saveClock(prefs, c) }
  fun changeCountdown(c: Countdown) { countdown = c; Look.saveCountdown(prefs, c) }
  fun changeNote(t: String) { note = t; prefs.sp.edit().putString("note", t).apply() }
  fun changeEnhance(v: Boolean) { autoEnhance = v; prefs.sp.edit().putBoolean("enhance", v).apply() }
  fun noteLaunch(pkg: String) { recents = (listOf(pkg) + recents.filter { it != pkg }).take(8); prefs.sp.edit().putString("recents", recents.joinToString(",")).apply() }
  fun replaceFavorites(l: List<String>) = updateFavorites(l)

  // Cached: only recomputed when apps / hidden / favorites actually change (not on every recomposition).
  val visibleApps: List<AppEntry> by androidx.compose.runtime.derivedStateOf { apps.filter { it.pkg !in hidden } }
  val favoriteApps: List<AppEntry> by androidx.compose.runtime.derivedStateOf {
    val byPkg = (if (appsLoaded) apps else dockSnapshot).associateBy { it.pkg }
    favorites.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden }
  }

  fun updateApps(list: List<AppEntry>) {
    if (appsLoaded && list == apps) return   // unchanged (memoized entries): nothing to recompose
    apps = list; appsLoaded = true; dockSnapshot = emptyList()
    if (!prefs.seeded && list.isNotEmpty()) {
      val installed = list.map { it.pkg }.toSet()
      var seed = Apps.SEED.filter { it in installed }
      if (seed.size < 4) seed = (seed + list.filter { it.leanback }.map { it.pkg }).distinct().take(6)
      updateFavorites(seed); prefs.seeded = true
    }
  }
  fun isFav(pkg: String) = pkg in favorites
  fun toggleFav(pkg: String) = updateFavorites(if (isFav(pkg)) favorites - pkg else favorites + pkg)
  fun moveFav(pkg: String, delta: Int) {
    val l = favorites.toMutableList(); val i = l.indexOf(pkg); val j = i + delta
    if (i < 0 || j !in l.indices) return
    l.removeAt(i); l.add(j, pkg); updateFavorites(l)
  }
  fun hide(pkg: String) { hidden = hidden + pkg; prefs.hidden = hidden }
  fun unhide(pkg: String) { hidden = hidden - pkg; prefs.hidden = hidden }
  private fun updateFavorites(l: List<String>) { favorites = l.distinct().take(MAX_DOCK); prefs.favorites = favorites }
  fun changeWallpaper(i: Int) { wallpaperChoice = i; prefs.wallpaper = i }
  fun changeAmbientSource(v: Int) { ambientSource = v; prefs.ambientSource = v }
  fun setIdle(m: Int) { idleMinutes = m; prefs.idleMinutes = m }
  fun set24h(v: Boolean) { use24h = v; prefs.use24h = v }
  fun changeCelsius(v: Boolean) { celsius = v; prefs.celsius = v }
  fun changeShowWeather(v: Boolean) { showWeather = v; prefs.showWeather = v }
  fun setWeatherJson(json: String) { WeatherApi.parse(json)?.let { weather = it; prefs.weatherJson = json } }
}

/** A TV input for the Inputs card. */
data class InputUi(val short: String, val label: String, val sub: String, val action: () -> Unit)


/** Photos uploaded from the phone, kept in app-private storage (no access to the TV's shared storage). */
object MyPhotos {
  const val LONG_EDGE = 2560   // UI renders at 1920x1080; headroom for the 1.2x Ken Burns zoom
  const val MAX_PHOTOS = 400
  private fun dir(ctx: Context) = java.io.File(ctx.filesDir, "photos").apply { mkdirs() }
  private fun thumbs(ctx: Context) = java.io.File(ctx.filesDir, "thumbs").apply { mkdirs() }

  fun list(ctx: Context): List<Photo> =
    (dir(ctx).listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedByDescending { it.name }.map { Photo(file = it.absolutePath) }

  fun thumbOf(ctx: Context, p: Photo) = java.io.File(thumbs(ctx), java.io.File(p.file!!).name)

  fun delete(ctx: Context, p: Photo) { p.file?.let { java.io.File(it).delete(); thumbOf(ctx, p).delete(); PhotoMetaStore.delete(ctx, p) }; resetCount() }

  private val slots = java.util.concurrent.atomic.AtomicInteger(-1)
  private val decodeGate = java.util.concurrent.Semaphore(1)   // re-encodes are memory heavy: one at a time
  private val seq = java.util.concurrent.atomic.AtomicInteger(0)

  /** Result of storing one upload: the stored file, or why it was refused. */
  data class Stored(val file: java.io.File?, val error: String? = null, val fastPath: Boolean = false)

  /**
   * Stores an uploaded image. Fast path (what the phone page sends): an upright JPEG already at display size is
   * moved into place as-is: no decode, no re-encode, so parallel uploads are limited by Wi-Fi, not the TV's CPU.
   * Anything else (HEIC, PNG, huge or rotated originals) is decoded, downscaled and re-encoded once.
   * Thumbnails and face/quality analysis happen afterwards on the background [Ingest] worker.
   */
  fun store(ctx: Context, src: java.io.File): Stored {
    val d = dir(ctx)
    synchronized(slots) { if (slots.get() < 0) slots.set(d.list()?.size ?: 0); if (slots.get() >= MAX_PHOTOS) return Stored(null, "The TV already has $MAX_PHOTOS photos. Delete some first."); slots.incrementAndGet() }
    var ok = false
    try {
      if (ctx.filesDir.usableSpace < 300L * 1024 * 1024) return Stored(null, "The TV is low on storage.")
      val name = "${System.currentTimeMillis()}_${seq.incrementAndGet() % 1000}_${(100..999).random()}.jpg"
      val out = java.io.File(d, name)
      val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(src.path, o)
      val upright = try { android.media.ExifInterface(src.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) <= 1 } catch (_: Exception) { true }
      if (o.outMimeType == "image/jpeg" && o.outWidth > 0 && maxOf(o.outWidth, o.outHeight) <= LONG_EDGE + 16 && upright) {
        val tmp = java.io.File(d, ".$name.part")
        if (!src.renameTo(tmp)) src.copyTo(tmp, true)
        if (!tmp.renameTo(out)) { tmp.delete(); return Stored(null, "Couldn't save on the TV.") }
        ok = true; return Stored(out, fastPath = true)
      }
      decodeGate.acquire()
      try {
        val bmp = decodeScaled(src, LONG_EDGE) ?: return Stored(null, "Unsupported image.")
        val tmp = java.io.File(d, ".$name.part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        if (!tmp.renameTo(out)) { tmp.delete(); return Stored(null, "Couldn't save on the TV.") }
        ok = true; return Stored(out)
      } finally { decodeGate.release() }
    } finally { if (!ok) slots.decrementAndGet() }
  }

  /** Kept for callers/tests that just want a yes/no. */
  fun save(ctx: Context, src: java.io.File): Boolean = store(ctx, src).file?.also { Ingest.enqueue(ctx, Photo(file = it.absolutePath)) } != null

  /** Small preview for the "My photos" grid (made by the background worker). */
  fun makeThumb(ctx: Context, f: java.io.File, bmp: Bitmap) {
    val sc = 480f / maxOf(bmp.width, bmp.height)
    val th = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
    java.io.File(thumbs(ctx), f.name).outputStream().use { th.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    if (th !== bmp) th.recycle()
  }
  fun resetCount() { synchronized(slots) { slots.set(-1) } }

  internal fun decodeScaled(f: java.io.File, longEdge: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) try {
      return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->   // handles HEIC + EXIF rotation
        val w = info.size.width; val h = info.size.height; val m = maxOf(w, h)
        if (m > longEdge) { val sc = longEdge.toFloat() / m; d.setTargetSize((w * sc).toInt().coerceAtLeast(1), (h * sc).toInt().coerceAtLeast(1)) }
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (_: Throwable) {}
    return try {
      val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
      var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= longEdge) s *= 2
      var b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
      val m = maxOf(b.width, b.height)
      if (m > longEdge) { val sc = longEdge.toFloat() / m; b = Bitmap.createScaledBitmap(b, (b.width * sc).toInt(), (b.height * sc).toInt(), true) }
      val rot = try { when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } catch (_: Exception) { 0f }
      if (rot != 0f) Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(rot) }, true) else b
    } catch (_: Throwable) { null }
  }
}

/** Normalizes every app icon into the same fully-filled square so the dock/grid look uniform. */
object Tiles {
  fun make(icon: Bitmap?, adaptive: Boolean, banner: Bitmap?, px: Int): Bitmap? {
    if (icon != null && adaptive) return icon                       // adaptive layers already rendered edge-to-edge
    if (icon != null) legacy(icon, px)?.let { return it }
    if (banner != null) return fromBanner(banner, px)
    return null
  }

  private fun alphaBounds(b: Bitmap, px: IntArray): android.graphics.Rect? {
    val w = b.width; val h = b.height
    var l = w; var t = h; var r = -1; var bt = -1
    for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 24) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y }
    return if (r < 0) null else android.graphics.Rect(l, t, r + 1, bt + 1)
  }

  /** Average colour of opaque pixels near the content's outer edge (the icon's own "container" colour). */
  private fun edgeColor(px: IntArray, w: Int, rc: android.graphics.Rect): Int {
    val band = maxOf(2, minOf(rc.width(), rc.height()) / 14)
    var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
    for (y in rc.top until rc.bottom) for (x in rc.left until rc.right) {
      val edge = x - rc.left < band || rc.right - 1 - x < band || y - rc.top < band || rc.bottom - 1 - y < band
      if (!edge) continue
      val c = px[y * w + x]; if ((c ushr 24) < 200) continue
      rs += c shr 16 and 255; gs += c shr 8 and 255; bs += c and 255; n++
    }
    return if (n == 0) 0xFF3A3F66.toInt() else android.graphics.Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
  }

  private fun lum(c: Int) = (0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)) / 255.0

  private fun legacy(src: Bitmap, px: Int): Bitmap? {
    val b = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
    val w = b.width; val h = b.height
    val p = IntArray(w * h); b.getPixels(p, 0, w, 0, 0, w, h)
    val rc = alphaBounds(b, p) ?: return null
    var opaque = 0
    for (y in rc.top until rc.bottom) for (x in rc.left until rc.right) if ((p[y * w + x] ushr 24) > 200) opaque++
    val coverage = opaque / (rc.width() * rc.height()).toFloat()
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    val edge = edgeColor(p, w, rc)
    fun place(scaleOfTile: Float) {
      val sc = px * scaleOfTile / maxOf(rc.width(), rc.height())
      val dw = rc.width() * sc; val dh = rc.height() * sc
      c.drawBitmap(b, rc, android.graphics.RectF((px - dw) / 2, (px - dh) / 2, (px + dw) / 2, (px + dh) / 2), paint)
    }
    when {
      // Filled container (rounded square, padded square): crop to it and overscan past its rounded corners.
      coverage >= 0.86f -> { c.drawColor(edge); place(1.10f) }
      // Shaped container (circle, shield…): extend its own edge colour to the corners, keep the shape full size.
      coverage >= 0.55f -> { c.drawColor(edge); place(1.0f) }
      // Bare logo on transparency: put it on a contrasting tinted tile.
      else -> {
        val logo = edge
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(logo, hsv)
        val bg = when {
          lum(logo) > 0.55 && hsv[1] < 0.2f -> 0xFF15161B.toInt()          // white/grey logo → near-black tile
          lum(logo) > 0.55 -> darkenInt(saturated(logo), 0.62f)
          else -> lightenInt(logo, 0.88f)
        }
        c.drawColor(bg); place(0.66f)
      }
    }
    return out
  }

  private fun fromBanner(banner: Bitmap, px: Int): Bitmap {
    val w = banner.width; val h = banner.height
    val p = IntArray(w * h); banner.getPixels(p, 0, w, 0, 0, w, h)
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(out)
    c.drawColor(edgeColor(p, w, android.graphics.Rect(0, 0, w, h)))
    val dh = px * h / w.toFloat()
    c.drawBitmap(banner, null, android.graphics.RectF(0f, (px - dh) / 2, px.toFloat(), (px + dh) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return out
  }

  private fun saturated(c: Int): Int { val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv); hsv[1] = maxOf(hsv[1], 0.35f); return android.graphics.Color.HSVToColor(hsv) }
  private fun mix(a: Int, b: Int, t: Float) = android.graphics.Color.rgb(
    ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * t).toInt(),
    ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * t).toInt(),
    ((a and 255) + ((b and 255) - (a and 255)) * t).toInt())
  private fun darkenInt(c: Int, t: Float) = mix(c, 0xFF000000.toInt(), t)
  private fun lightenInt(c: Int, t: Float) = mix(c, 0xFFFFFFFF.toInt(), t)
}
