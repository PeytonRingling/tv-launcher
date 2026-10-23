package com.peyton.tvlauncher

import android.view.KeyEvent as NKey
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ================================================================ Shared kit for the eight "styled" homes
// Every style follows the same rules:
//  • one focus-progress driver ([FocusDriver.pos], a spring toward the focused index) feeds every focus visual,
//    read only inside graphicsLayer/draw lambdas (no recomposition per frame);
//  • explicit, predictable D-pad moves ([StyleNav]); DOWN past the last row opens All apps like the dock does;
//  • one [StyleFx]: an entry transition (first show and every return to the launcher) and a launch transition;
//  • ambient motion from [rememberAmbientTime]: frame-capped and paused when the launcher isn't resumed, an overlay is
//    open, or ambient mode is on.

val Vt323 = FontFamily(Font(R.font.vt323_regular))
val PressStart = FontFamily(Font(R.font.pressstart_regular))
val Bangers = FontFamily(Font(R.font.bangers_regular))
val Monoton = FontFamily(Font(R.font.monoton_regular))
val Playfair = FontFamily(Font(R.font.playfair_regular, FontWeight.Normal), Font(R.font.playfair_bold, FontWeight.Bold))
val DmSerif = FontFamily(Font(R.font.dmserif_regular))
val SpaceGrotesk = FontFamily(Font(R.font.spacegrotesk_light, FontWeight.Light), Font(R.font.spacegrotesk_regular, FontWeight.Normal), Font(R.font.spacegrotesk_bold, FontWeight.Bold))

val StyleSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 260f)
val StyleDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val StyleAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
const val ENTER_MS = 900
const val LAUNCH_MS = 520

/** Something you can pick on a styled home. */
class StyleItem(val key: String, val label: String, val app: AppEntry?, val kind: Kind) {
  enum class Kind { APP, ALL, SETTINGS }
  val tint: Color get() = app?.tint ?: if (kind == Kind.ALL) Color(0xFF5B6CFF) else Color(0xFF7A8699)
}

/** Dock apps, then All apps and Settings (so every style reaches both without the dock). Keys match HomeContent's prefix. */
fun styleItems(st: LauncherState, prefix: String): List<StyleItem> =
  st.favoriteApps.map { StyleItem("$prefix:${it.pkg}", it.label, it, StyleItem.Kind.APP) } +
    StyleItem("$prefix:all", "All apps", null, StyleItem.Kind.ALL) + StyleItem("$prefix:settings", "Settings", null, StyleItem.Kind.SETTINGS)

/** The single focus driver: [index] is the focused item, [pos] springs toward it; item i's focus is [k]. */
@Stable
class FocusDriver(internal val index: MutableIntState, val pos: Animatable<Float, AnimationVector1D>) {
  val focused: Int get() = index.intValue
  /** 0…1 focus of item [i], continuous while the spring travels. Read it in draw/layer lambdas only. */
  fun k(i: Int): Float = (1f - abs(pos.value - i)).coerceIn(0f, 1f)
  /** Wrapped distance for circular layouts (Space). */
  fun ringOffset(i: Int, n: Int): Float { var d = i - pos.value; val h = n / 2f; while (d > h) d -= n; while (d < -h) d += n; return d }
}

@Composable
fun rememberFocusDriver(count: Int, wrap: Boolean = false): FocusDriver {
  val index = remember { mutableIntStateOf(0) }
  val pos = remember { Animatable(0f) }
  val d = remember { FocusDriver(index, pos) }
  LaunchedEffect(index.intValue, count) {
    val target = index.intValue.toFloat()
    if (wrap && count > 0) {   // take the short way round, then renormalize so pos stays in [0, n)
      var from = pos.value; val h = count / 2f
      while (target - from > h) from += count; while (from - target > h) from -= count
      pos.snapTo(from)
    }
    pos.animateTo(target, StyleSpring)
  }
  return d
}

/** D-pad geometry: a grid with [cols] columns (1 = vertical list, n = single row); [wrap] loops a single row. */
class StyleNav(val n: Int, val cols: Int, val wrap: Boolean = false) {
  /** Target index for a key, [ALL] to open All apps, or null to let the key through. */
  fun move(i: Int, key: Int): Int? = when (key) {
    NKey.KEYCODE_DPAD_LEFT -> if (wrap) (i - 1 + n) % n else if (i % cols > 0) i - 1 else STAY
    NKey.KEYCODE_DPAD_RIGHT -> if (wrap) (i + 1) % n else if (i % cols < cols - 1 && i + 1 < n) i + 1 else STAY
    NKey.KEYCODE_DPAD_UP -> if (cols < n && i - cols >= 0) i - cols else STAY
    NKey.KEYCODE_DPAD_DOWN -> if (cols < n && i + cols < n) i + cols
      else if (cols < n && i / cols < (n - 1) / cols) n - 1 else ALL
    else -> null
  }
  companion object { const val ALL = -2; const val STAY = -1 }
}

/** Entry + launch transitions shared by every style. */
@Stable
class StyleFx(private val scope: CoroutineScope) {
  /** 0 → 1 (linear in time; each style eases its own phases) on first show and on every return to the launcher. */
  val enter = Animatable(0f)
  /** 0 → 1 while an app is being opened (the launch transition), then back to 0 off-screen. */
  val launch = Animatable(0f)
  var launching by mutableIntStateOf(-1); private set
  fun activate(env: HomeEnv, items: List<StyleItem>, i: Int) {
    val it = items.getOrNull(i) ?: return
    when (it.kind) {
      StyleItem.Kind.ALL -> env.st.allApps = true
      StyleItem.Kind.SETTINGS -> env.st.screen = Screen.SETTINGS
      StyleItem.Kind.APP -> if (launching < 0) scope.launch {
        launching = i
        launch.snapTo(0f); launch.animateTo(1f, tween(LAUNCH_MS, easing = androidx.compose.animation.core.LinearEasing))
        env.actions.launch(it.app!!)
        delay(900)      // the app is on top by now; reset behind it so coming back shows the entry transition
        launch.snapTo(0f); launching = -1
      }
    }
  }
}

@Composable
fun rememberStyleFx(env: HomeEnv): StyleFx {
  val scope = rememberCoroutineScope()
  val fx = remember { StyleFx(scope) }
  // verseSession ticks every time the launcher comes back to the foreground (repeatOnLifecycle STARTED).
  LaunchedEffect(env.st.verseSession) { fx.enter.snapTo(0f); fx.enter.animateTo(1f, tween(ENTER_MS, easing = androidx.compose.animation.core.LinearEasing)) }
  return fx
}

/**
 * The per-item modifier: focus requester (so HomeContent's focus restore works), focus → driver, explicit D-pad moves,
 * OK = launch transition, hold OK / MENU = the app menu.
 */
fun Modifier.styleItem(env: HomeEnv, items: List<StyleItem>, i: Int, drv: FocusDriver, fx: StyleFx, nav: StyleNav): Modifier = composed {
  val item = items[i]
  this
    .testTag(item.key)
    .focusRequester(env.req(item.key))
    .onFocusChanged { if (it.isFocused) { drv.index.intValue = i; env.onAppFocus(item.key) } }
    .onKeyEvent { e ->
      val n = e.nativeKeyEvent
      val t = nav.move(i, n.keyCode) ?: return@onKeyEvent false
      if (n.action != NKey.ACTION_DOWN) return@onKeyEvent true
      when (t) {
        StyleNav.STAY -> {}
        StyleNav.ALL -> if (n.repeatCount == 0) env.st.allApps = true
        else -> tryFocus(env.req(items[t].key))
      }
      true
    }
    .dpadClick({ fx.activate(env, items, i) }, item.app?.let { a -> { env.st.menuFor = a } })
    .focusProperties { canFocus = env.interactive }
    .focusable()
}

/**
 * Seconds of ambient motion, advanced at most [fps] times a second and only while it can be seen
 * (launcher resumed, no overlay, not in ambient mode). Read it only inside draw/layer lambdas.
 */
@Composable
fun rememberAmbientTime(env: HomeEnv, fps: Int = 30): State<Float> {
  val t = remember { mutableFloatStateOf(0f) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
  DisposableEffect(lifecycle) {
    val o = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
  }
  val preview = env.fade == null
  val run = resumed && !env.st.ambient && (env.interactive || preview)
  LaunchedEffect(run) {
    if (!run) return@LaunchedEffect
    val step = 1000L / fps; var base = -1L; var last = -step; val from = t.floatValue
    while (true) withFrameMillis { ms ->
      if (base < 0) base = ms
      if (ms - last >= step) { last = ms; t.floatValue = from + (ms - base) / 1000f; AmbientProbe.ticks++ }
    }
  }
  return t
}

/** Counts ambient ticks across every styled home (a plain counter; lets tests prove the motion really stops). */
object AmbientProbe { @Volatile @JvmField var ticks = 0L }

/** Idle seconds since the last key on this style (for attract modes). Reset by [StyleIdle.poke]. */
@Stable class StyleIdle { var lastKey by mutableLongStateOf(0L); fun poke(now: Long) { lastKey = now } }

/** App art for any style: the app's banner, else its uniform tile, else a glyph; All apps / Settings get drawn glyphs. */
@Composable
fun ItemArt(item: StyleItem, modifier: Modifier = Modifier, preferBanner: Boolean = false, glyph: Color = Color.White, glyphLift: Dp = 0.dp) {
  val app = item.app
  Box(modifier.background(Brush.linearGradient(listOf(lighten(item.tint, 0.12f), darken(item.tint, 0.35f)))), Alignment.Center) {
    when {
      app != null && preferBanner && app.banner != null -> Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
      app?.tile != null -> Image(app.tile, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
      app != null && app.banner != null -> Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
      app != null -> T(app.label.take(1).uppercase(), 28.androidx_sp, color = glyph)
      item.kind == StyleItem.Kind.ALL -> Canvas(Modifier.offset(y = -glyphLift).fillMaxSize(0.38f)) {
        val d = size.width / 3f; for (a in 0..2) for (b in 0..2) drawCircle(glyph, d * 0.24f, Offset(d * (a + 0.5f), d * (b + 0.5f)))
      }
      else -> Image(rememberVectorPainter(Icons.Filled.Settings), null, Modifier.offset(y = -glyphLift).fillMaxSize(0.42f), colorFilter = ColorFilter.tint(glyph))
    }
  }
}
private val Int.androidx_sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Small data helpers the styles share. */
object StyleData {
  fun weatherLine(st: LauncherState): String? {
    val w = st.weather ?: return null
    return "${WeatherApi.fmt(w.tempF, st.celsius)} ${WeatherApi.describe(w.code)}"
  }
  fun place(st: LauncherState) = st.place?.label
  fun notice(st: LauncherState): String? = st.verse?.let { v -> if (st.verseNotice) VerseText.shortNotice(st.verseAbbr, v.copyright) else null }
  fun dayOfYear(): Int = java.time.LocalDate.now().dayOfYear
}

/** API.Bible FUMS: a style that shows the verse reports it exactly like the verse card does. */
@Composable
fun VerseFums(st: LauncherState, actions: Actions) {
  val token = st.verse?.fums
  LaunchedEffect(token, st.verseSession) { if (token != null) actions.reportFums(token) }
}

/** Deterministic pseudo-random in [0,1) for procedural art (stable across frames and runs). */
fun hash01(i: Int, salt: Int = 0): Float {
  var x = i * 374761393 + salt * 668265263; x = (x xor (x ushr 13)) * 1274126177; x = x xor (x ushr 16)
  return (x and 0xFFFFFF) / 16777216f
}

/** A procedurally baked bitmap (wood grain, paper, halftone…): generated once per size/key and cached for the process. */
object Baked {
  private val cache = HashMap<String, ImageBitmap>()
  fun get(key: String, w: Int, h: Int, px: (x: Int, y: Int) -> Int): ImageBitmap = synchronized(cache) {
    cache.getOrPut("$key:$w:$h") {
      val a = IntArray(w * h); for (y in 0 until h) for (x in 0 until w) a[y * w + x] = px(x, y)
      android.graphics.Bitmap.createBitmap(a, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
  }
  fun argb(a: Float, r: Float, g: Float, b: Float): Int =
    ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
}

/** Tiny smooth value noise for baked textures. */
fun vnoise(x: Float, y: Float, salt: Int = 0): Float {
  val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt(); val xf = x - xi; val yf = y - yi
  fun h(a: Int, b: Int) = hash01(a * 7919 + b * 104729, salt)
  val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf)
  val a = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * u
  val b = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * u
  return a + (b - a) * v
}

/** [vnoise] on a lattice that wraps every [px] × [py] cells, so a texture baked from it tiles seamlessly. */
fun pnoise(x: Float, y: Float, px: Int, py: Int, salt: Int = 0): Float {
  val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt(); val xf = x - xi; val yf = y - yi
  fun h(a: Int, b: Int) = hash01(Math.floorMod(a, px) * 7919 + Math.floorMod(b, py) * 104729, salt)
  val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf)
  val a = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * u
  val b = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * u
  return a + (b - a) * v
}

fun Dp.half() = this / 2

/** A baked texture repeated across whatever it fills. */
fun tiledBrush(img: ImageBitmap): Brush = ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Repeated))

/** Shared ambient-run helper so draw code can ask whether to skip expensive frames. */
fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Staggered entry progress for item [i]: starts at [start] + [step]·i, lasts [dur], and always finishes by the end of the
 * entry (the delay is capped), so no item is left part-faded at rest however many apps are pinned.
 */
fun stagger(enter: Float, start: Float, step: Float, i: Int, dur: Float): Float {
  if (enter >= 1f) return 1f
  val d = minOf(step * i, (0.98f - start - dur).coerceAtLeast(0f))
  return ((enter - start - d) / dur).coerceIn(0f, 1f)
}
