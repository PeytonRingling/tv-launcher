package com.peyton.tvlauncher

import android.view.KeyEvent as NKey
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val Accent = Color(0xFF9DB8FF)

val Inter = androidx.compose.ui.text.font.FontFamily(
  androidx.compose.ui.text.font.Font(R.font.inter_200, FontWeight.ExtraLight),
  androidx.compose.ui.text.font.Font(R.font.inter_300, FontWeight.Light),
  androidx.compose.ui.text.font.Font(R.font.inter_400, FontWeight.Normal),
  androidx.compose.ui.text.font.Font(R.font.inter_500, FontWeight.Medium),
  androidx.compose.ui.text.font.Font(R.font.inter_600, FontWeight.SemiBold),
  androidx.compose.ui.text.font.Font(R.font.inter_700, FontWeight.Bold),
)

/**
 * Pre-blurred wallpaper that glass surfaces sample at their own screen position (cheap fake backdrop blur).
 * [prev]/[fade] let the wallpaper crossfade; [lum] is a coarse linear-luminance grid used to pick how dark each card must be.
 */
@Stable
class GlassBg(val img: ImageBitmap?, val screen: androidx.compose.ui.unit.IntSize, val prev: ImageBitmap? = null, val fade: State<Float>? = null,
              /** For scaled live previews (Settings): root position of the preview and its scale, so glass still samples correctly. */
              val previewOrigin: State<Offset>? = null, val previewScale: Float = 1f) {
  fun toScreen(o: Offset): Offset = previewOrigin?.let { (o - it.value) / previewScale } ?: o
  fun toScreen(r: androidx.compose.ui.geometry.Rect) = if (previewOrigin == null) r else androidx.compose.ui.geometry.Rect(toScreen(r.topLeft), r.size / previewScale)
  val lum: FloatArray? by lazy { img?.let { lumGrid(it) } }
  companion object {
    const val GW = 48; const val GH = 27
    fun lumGrid(img: ImageBitmap): FloatArray {
      val pm = img.toPixelMap(); val out = FloatArray(GW * GH)
      for (gy in 0 until GH) for (gx in 0 until GW) {
        val c = pm[(gx * img.width / GW + img.width / GW / 2).coerceIn(0, img.width - 1), (gy * img.height / GH + img.height / GH / 2).coerceIn(0, img.height - 1)]
        out[gy * GW + gx] = c.luminance()
      }
      return out
    }
  }
  /** Black overlay alpha so white text over this screen rect reaches ~4.5:1 against the brightest part of the blurred wallpaper. */
  fun darknessFor(r: androidx.compose.ui.geometry.Rect, min: Float, max: Float): Float {
    val g = lum ?: return min; if (screen.width <= 0) return min
    val crop = cropRect(img!!.width, img.height, screen.width, screen.height)
    var mx = 0f; var sum = 0f; var n = 0
    var y = r.top; val stepY = r.height / 6f; val stepX = r.width / 8f
    while (y <= r.bottom) { var x = r.left; while (x <= r.right) {
      val gx = (((x - crop.left) / crop.width) * GW).toInt().coerceIn(0, GW - 1); val gy = (((y - crop.top) / crop.height) * GH).toInt().coerceIn(0, GH - 1)
      val l = g[gy * GW + gx]; mx = maxOf(mx, l); sum += l; n++; x += stepX }; y += stepY }
    val l = (sum / n) * 0.4f + mx * 0.6f
    // (1 - a) * L + 0.05 ≤ 1.05 / 4.5  →  a ≥ 1 - 0.183 / L
    return (1f - 0.183f / l.coerceAtLeast(0.01f)).coerceIn(min, max)
  }
}
val LocalGlass = compositionLocalOf { GlassBg(null, androidx.compose.ui.unit.IntSize.Zero) }

fun cropRect(iw: Int, ih: Int, sw: Int, sh: Int): androidx.compose.ui.geometry.Rect {
  val sc = maxOf(sw / iw.toFloat(), sh / ih.toFloat()); val w = iw * sc; val h = ih * sc
  return androidx.compose.ui.geometry.Rect((sw - w) / 2, (sh - h) / 2, (sw + w) / 2, (sh + h) / 2)
}

/** Cached "darken by a" colour filters (quantized) so the glass is ONE opaque bitmap pass with no per-frame allocation. */
private val darkFilters = arrayOfNulls<ColorFilter>(65)
private fun darkFilter(a: Float): ColorFilter {
  val i = (a.coerceIn(0f, 1f) * 64).toInt()
  return darkFilters[i] ?: run {
    val m = 1f - i / 64f
    ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(m, 0f, 0f, 0f, 0f, 0f, m, 0f, 0f, 0f, 0f, 0f, m, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
  }.also { darkFilters[i] = it }
}

/**
 * Fake frosted glass: samples the pre-blurred (384x216) wallpaper at this surface's screen position, darkened in the
 * same pass via a colour filter. [fixedOrigin] pins sampling to a resting position for surfaces that slide (the drawer),
 * so sliding is a pure layer translation: no re-recording, no re-sampling per frame.
 */
fun Modifier.frosted(
  shape: androidx.compose.ui.graphics.Shape,
  tint: Color = Color(0x0FFFFFFF), edge: Color = Color(0x40FFFFFF), sheen: Float = 0.06f,
  minDark: Float = 0.42f, maxDark: Float = 0.68f,
  fixedOrigin: Offset? = null,
): Modifier = composed {
  val g = LocalGlass.current
  val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
  var rect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
  val target = remember(g, rect, minDark, maxDark) {
    val r = if (fixedOrigin != null && rect.width > 0f) androidx.compose.ui.geometry.Rect(fixedOrigin, rect.size) else rect
    if (r.width > 0f) g.darknessFor(r, minDark, maxDark) else minDark
  }
  val dark = animateFloatAsState(target, tween(900), label = "dark")
  val edgeBrush = remember(edge) { Brush.verticalGradient(listOf(edge, edge.copy(alpha = edge.alpha * 0.3f))) }
  this
    .onGloballyPositioned { holder[0] = it; val r = g.toScreen(it.boundsInRoot()); if (r.size != rect.size || fixedOrigin == null) rect = r }
    .clip(shape)
    .drawWithCache {
      val sheenBrush = if (sheen > 0f) Brush.verticalGradient(listOf(Color.White.copy(alpha = sheen), Color.Transparent), endY = size.height * 0.5f) else null
      onDrawBehind {
        val pos = fixedOrigin ?: holder[0]?.takeIf { it.isAttached }?.positionInRoot()?.let { g.toScreen(it) } ?: Offset.Zero
        val filter = darkFilter(dark.value)
        fun sample(img: ImageBitmap, a: Float) {
          val r = cropRect(img.width, img.height, g.screen.width, g.screen.height)
          drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
            IntOffset(kotlin.math.round(r.left - pos.x).toInt(), kotlin.math.round(r.top - pos.y).toInt()),
            IntSize(kotlin.math.round(r.width).toInt(), kotlin.math.round(r.height).toInt()), alpha = a, colorFilter = filter, filterQuality = FilterQuality.Low)
        }
        if (g.screen.width > 0 && g.img != null) {
          val f = g.fade?.value ?: 1f
          if (g.prev != null && f < 1f) sample(g.prev, 1f)
          sample(g.img, f)
        } else drawRect(Color(0xFF14161E))
        if (tint.alpha > 0f) drawRect(tint)
        sheenBrush?.let { drawRect(it) }
      }
    }
    .border(1.dp, edgeBrush, shape)
}

/** Draws an image exactly like ContentScale.Crop/center, so glass sampling lines up with it; crossfades from [prev]. */
@Composable
fun Wallpaper(img: ImageBitmap?, prev: ImageBitmap? = null, fade: State<Float>? = null) = Canvas(Modifier.fillMaxSize()) {
  drawRect(Color(0xFF0B0D14))
  fun one(i: ImageBitmap, a: Float) {
    val r = cropRect(i.width, i.height, size.width.toInt(), size.height.toInt())
    drawImage(i, IntOffset.Zero, IntSize(i.width, i.height), IntOffset(r.left.toInt(), r.top.toInt()), IntSize(r.width.toInt(), r.height.toInt()), alpha = a, filterQuality = FilterQuality.Medium)
  }
  val f = fade?.value ?: 1f
  if (prev != null && f < 1f) one(prev, 1f)
  img?.let { one(it, f) }
}
/** Soft shadow that keeps small white text legible over bright photos. */
val TextShadow = Shadow(Color(0x99000000), Offset(0f, 1.5f), 6f)
val Ink = Color(0xFFF4F5FA)

@Composable
fun T(
  text: String, size: TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal,
  color: Color = Ink, spacing: TextUnit = 0.sp, align: TextAlign = TextAlign.Start, maxLines: Int = 1,
  shadow: Shadow? = null, family: androidx.compose.ui.text.font.FontFamily = Inter,
) = BasicText(
  text, modifier,
  style = TextStyle(color = color, fontSize = size, fontWeight = weight, letterSpacing = spacing, textAlign = align, fontFamily = family, shadow = shadow,
    fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.None),
  maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, fill: Color = Color(0x1FFFFFFF), edge: Color = Color(0x2EFFFFFF)) =
  this.clip(shape).background(fill).border(1.dp, Brush.verticalGradient(listOf(edge, edge.copy(alpha = edge.alpha * 0.25f))), shape)

/** D-pad friendly click: acts on key-up only if the key-down landed here; holding OK (or MENU) = long press. */
fun Modifier.dpadClick(onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier = composed {
  val click by rememberUpdatedState(onClick)
  val long by rememberUpdatedState(onLong)
  var sawDown by remember { mutableStateOf(false) }
  var longFired by remember { mutableStateOf(false) }
  this
    .onKeyEvent { e ->
      val k = e.nativeKeyEvent
      when (k.keyCode) {
        NKey.KEYCODE_DPAD_CENTER, NKey.KEYCODE_ENTER, NKey.KEYCODE_NUMPAD_ENTER, NKey.KEYCODE_BUTTON_A -> {
          if (k.action == NKey.ACTION_DOWN) {
            if (k.repeatCount == 0) { sawDown = true; longFired = false }
            else if (long != null && !longFired && sawDown) { longFired = true; long?.invoke() }
          } else if (k.action == NKey.ACTION_UP) {
            if (sawDown && !longFired) click()
            sawDown = false; longFired = false
          }
          true
        }
        NKey.KEYCODE_MENU -> { if (k.action == NKey.ACTION_UP) long?.invoke(); long != null }
        else -> false
      }
    }
    .pointerInput(Unit) { detectTapGestures(onTap = { click() }, onLongPress = { long?.invoke() }) }
}

fun tryFocus(fr: FocusRequester?) { try { fr?.requestFocus() } catch (_: Exception) {} }

// ---------------------------------------------------------------- Backdrop

@Composable
fun Backdrop(img: ImageBitmap?) {
  Box(Modifier.fillMaxSize().background(Color(0xFF07080D))) {
    if (img != null) Image(img, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f; scaleY = 1.1f },
      contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
    else Box(Modifier.fillMaxSize().drawWithCache {
      val a = Brush.radialGradient(listOf(Color(0xFF3B2F7A), Color.Transparent), Offset(size.width * 0.85f, 0f), size.width * 0.7f)
      val b = Brush.radialGradient(listOf(Color(0xFF14506E), Color.Transparent), Offset(0f, size.height), size.width * 0.6f)
      onDrawBehind { drawRect(a); drawRect(b) }
    })
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x59000000), Color(0x8C000000), Color(0xE605060A)))))
    Box(Modifier.fillMaxSize().drawWithCache {
      val v = Brush.radialGradient(listOf(Color.Transparent, Color(0x8C000000)), Offset(size.width * 0.5f, size.height * 0.35f), size.maxDimension * 0.8f)
      onDrawBehind { drawRect(v) }
    })
  }
}

// ---------------------------------------------------------------- Clock & weather

@Composable
fun rememberNow(): Long {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) { while (true) { delay(1000 - now % 1000 + 10); now = System.currentTimeMillis() } }
  return now
}

fun fmtTime(t: Long, h24: Boolean): String = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.US).format(Date(t))
fun fmtAmPm(t: Long): String = SimpleDateFormat("a", Locale.US).format(Date(t))
fun fmtDate(t: Long): String = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date(t))

@Composable
fun WeatherGlyph(sky: Sky, day: Boolean, size: Dp) = Canvas(Modifier.size(size)) {
  val s = this.size.minDimension
  val sunC = Color(0xFFFFCF66); val moonC = Color(0xFFE6E4FF); val cloudC = Color(0xFFE9EEF7); val dark = Color(0xFFAEB8CC)
  fun sun(c: Offset, r: Float) {
    drawCircle(sunC, r, c)
    for (i in 0 until 8) {
      val a = i * PI / 4
      drawLine(sunC, c + Offset((cos(a) * r * 1.45f).toFloat(), (sin(a) * r * 1.45f).toFloat()),
        c + Offset((cos(a) * r * 1.95f).toFloat(), (sin(a) * r * 1.95f).toFloat()), s * 0.055f, StrokeCap.Round)
    }
  }
  fun moon(c: Offset, r: Float) {
    val p = Path.combine(PathOperation.Difference,
      Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) },
      Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.55f, -r * 0.35f), r * 0.85f)) })
    drawPath(p, moonC)
  }
  fun orb(c: Offset, r: Float) = if (day) sun(c, r) else moon(c, r * 1.3f)
  fun cloud(c: Offset, w: Float, col: Color) {
    drawCircle(col, w * 0.2f, c + Offset(-w * 0.2f, w * 0.02f))
    drawCircle(col, w * 0.28f, c + Offset(w * 0.04f, -w * 0.08f))
    drawCircle(col, w * 0.19f, c + Offset(w * 0.27f, w * 0.04f))
    drawRoundRect(col, c + Offset(-w * 0.4f, w * 0.02f), Size(w * 0.86f, w * 0.21f), CornerRadius(w * 0.1f))
  }
  val mid = Offset(s / 2, s / 2)
  when (sky) {
    Sky.SUN -> orb(mid, s * 0.2f)
    Sky.PARTLY -> { orb(Offset(s * 0.38f, s * 0.36f), s * 0.15f); cloud(Offset(s * 0.56f, s * 0.6f), s * 0.62f, cloudC) }
    Sky.CLOUD -> { cloud(Offset(s * 0.6f, s * 0.42f), s * 0.5f, dark); cloud(Offset(s * 0.46f, s * 0.58f), s * 0.72f, cloudC) }
    Sky.FOG -> { cloud(Offset(s * 0.5f, s * 0.38f), s * 0.66f, cloudC); for (i in 0..1) drawLine(dark, Offset(s * 0.18f, s * (0.68f + i * 0.13f)), Offset(s * 0.82f, s * (0.68f + i * 0.13f)), s * 0.06f, StrokeCap.Round) }
    Sky.RAIN, Sky.STORM, Sky.SNOW -> {
      cloud(Offset(s * 0.5f, s * 0.36f), s * 0.7f, cloudC)
      for (i in 0..2) {
        val x = s * (0.3f + i * 0.2f)
        when (sky) {
          Sky.SNOW -> drawCircle(Color.White, s * 0.045f, Offset(x, s * 0.76f + (i % 2) * s * 0.08f))
          Sky.RAIN -> drawLine(Accent, Offset(x, s * 0.66f), Offset(x - s * 0.06f, s * 0.86f), s * 0.05f, StrokeCap.Round)
          else -> {}
        }
      }
      if (sky == Sky.STORM) drawPath(Path().apply {
        moveTo(s * 0.54f, s * 0.56f); lineTo(s * 0.4f, s * 0.78f); lineTo(s * 0.52f, s * 0.78f); lineTo(s * 0.44f, s * 0.96f)
        lineTo(s * 0.66f, s * 0.7f); lineTo(s * 0.54f, s * 0.7f); close()
      }, sunC)
    }
  }
}

// ---------------------------------------------------------------- App tile

@Composable
fun AppTile(
  app: AppEntry, width: Dp, focusRequester: FocusRequester,
  moving: Boolean = false, onFocused: () -> Unit = {}, onClick: () -> Unit, onLong: () -> Unit,
  onMove: (Int) -> Unit = {}, onMoveDone: () -> Unit = {}, forceFocus: Boolean = false, canFocus: Boolean = true,
  /** When true (e.g. while the drawer snapshot slides) focus visuals jump instead of animating, keeping the snapshot valid. */
  snapFocus: () -> Boolean = { false },
) {
  var focused by remember { mutableStateOf(forceFocus) }
  val f = focused || forceFocus
  // Animation values are only read inside graphicsLayer/draw lambdas → no recomposition or relayout per frame.
  val k = remember { androidx.compose.animation.core.Animatable(if (f) 1f else 0f) }
  LaunchedEffect(f) { val t = if (f) 1f else 0f; if (snapFocus()) k.snapTo(t) else k.animateTo(t, spring(dampingRatio = 0.8f, stiffness = 420f)) }
  val h = width * 9f / 16f
  val shape = remember(width) { RoundedCornerShape(width * 0.085f) }
  val halo = remember(app.tint) { lighten(app.tint, 0.25f) }
  val bg = remember(app.tint) { Brush.linearGradient(listOf(lighten(app.tint, 0.18f), darken(app.tint, 0.35f))) }
  val moveMod = if (moving) Modifier.onKeyEvent { e ->
    val n = e.nativeKeyEvent
    when (n.keyCode) {
      NKey.KEYCODE_DPAD_LEFT -> { if (n.action == NKey.ACTION_DOWN) onMove(-1); true }
      NKey.KEYCODE_DPAD_RIGHT -> { if (n.action == NKey.ACTION_DOWN) onMove(1); true }
      NKey.KEYCODE_DPAD_CENTER, NKey.KEYCODE_ENTER, NKey.KEYCODE_BACK -> { if (n.action == NKey.ACTION_UP) onMoveDone(); true }
      NKey.KEYCODE_DPAD_UP, NKey.KEYCODE_DPAD_DOWN -> true
      else -> false
    }
  } else Modifier
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.zIndex(if (f) 1f else 0f).width(width)) {
    Box(
      Modifier
        .size(width, h)
        .graphicsLayer { val sc = 1f + 0.10f * k.value; scaleX = sc; scaleY = sc }
        .drawBehind {
          val g = k.value
          if (g > 0.01f) {
            val steps = 9; val maxSpread = 24.dp.toPx(); val r = 20.dp.toPx()
            for (i in steps downTo 1) {
              val sp = maxSpread * i / steps
              drawRoundRect(halo, Offset(-sp, -sp + 4.dp.toPx()), Size(size.width + sp * 2, size.height + sp * 2), CornerRadius(r + sp), alpha = 0.075f * g)
            }
          }
        }
        .then(moveMod)
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .dpadClick(onClick, onLong)
        .focusProperties { this.canFocus = canFocus }
        .focusable()
        .clip(shape)
        .background(bg)
        .drawWithCache {
          val outline = shape.createOutline(size, layoutDirection, this)
          val sheenBrush = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color.Transparent), Offset.Zero, Offset(size.width * 0.9f, size.height))
          val stroke = Stroke(1.5.dp.toPx())
          onDrawWithContent {
            drawContent()
            val g = k.value
            if (g > 0.01f) drawRect(sheenBrush, alpha = g.coerceAtMost(1f))
            if (!moving) drawOutline(outline, Color.White, alpha = (0.12f + 0.78f * g).coerceIn(0f, 1f), style = stroke)
          }
        }
    ) {
      if (app.banner != null) Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
      else Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        UniformIcon(app, h * 0.5f)
        Spacer(Modifier.height(h * 0.06f))
        T(app.label, (h.value * 0.105f).coerceIn(10f, 15f).sp, weight = FontWeight.SemiBold, color = Ink, shadow = TextShadow)
      }
      if (moving) MovingRing(shape)
    }
    Box(Modifier.height(30.dp).fillMaxWidth(), Alignment.BottomCenter) {
      if (moving) Row(verticalAlignment = Alignment.CenterVertically) {
        Image(rememberVectorPainter(Icons.Filled.KeyboardArrowLeft), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Accent))
        T("Move  ·  OK when done", 12.sp, color = Accent, weight = FontWeight.Medium)
        Image(rememberVectorPainter(Icons.Filled.KeyboardArrowRight), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Accent))
      } else T(app.label, 14.sp, Modifier.graphicsLayer { alpha = k.value; translationY = (1f - k.value) * -6.dp.toPx() }, weight = FontWeight.Medium, align = TextAlign.Center, shadow = TextShadow)
    }
  }
}

/** Pulsing accent ring; only composed while moving so idle tiles never run an infinite animation. */
@Composable
fun BoxScope.MovingRing(shape: androidx.compose.ui.graphics.Shape, width: Dp = 3.dp) {
  val pulse = rememberInfiniteTransition(label = "p").animateFloat(0.45f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pp")
  Box(Modifier.matchParentSize().drawWithCache {
    val o = shape.createOutline(size, layoutDirection, this); val st = Stroke(width.toPx())
    onDrawBehind { drawOutline(o, Accent, alpha = pulse.value, style = st) }
  })
}

val IconSquircle = RoundedCornerShape(27)

/** The same squircle for every app: normalized full-bleed tile + identical inner highlight and hairline border. */
@Composable
fun UniformIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
  val bg = remember(app.tint) { Brush.linearGradient(listOf(lighten(app.tint, 0.14f), darken(app.tint, 0.32f))) }
  Box(modifier.size(size).clip(IconSquircle).background(bg).drawWithCache {
    val hl = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), endY = this.size.height * 0.55f)
    val o = IconSquircle.createOutline(this.size, layoutDirection, this); val st = Stroke(1.dp.toPx())
    onDrawWithContent { drawContent(); drawRect(hl); drawOutline(o, Color.White, alpha = 0.14f, style = st) }
  }, Alignment.Center) {
    val t = app.tile
    if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
    else T(app.label.take(1).uppercase(), (size.value * 0.42f).sp, weight = FontWeight.Light)
  }
}

fun lighten(c: Color, f: Float) = lerpColor(c, Color.White, f)
fun darken(c: Color, f: Float) = lerpColor(c, Color.Black, f)
/** Clamped: spring animations overshoot past 1, and Color() throws outside 0..1. */
fun lerpColor(a: Color, b: Color, t0: Float): Color { val t = t0.coerceIn(0f, 1f); return Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t) }

// ---------------------------------------------------------------- Round icon button

@Composable
fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, focusRequester: FocusRequester = remember { FocusRequester() }) {
  var focused by remember { mutableStateOf(false) }
  val k by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "rb")
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
    Box(
      Modifier.size(52.dp)
        .graphicsLayer { scaleX = 1f + 0.12f * k; scaleY = 1f + 0.12f * k }
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused }
        .dpadClick(onClick)
        .focusable()
        .glass(CircleShape, fill = lerpColor(Color(0x22FFFFFF), Color(0xF2FFFFFF), k)),
      Alignment.Center,
    ) {
      Image(androidx.compose.ui.graphics.vector.rememberVectorPainter(icon), label, Modifier.size(26.dp),
        colorFilter = ColorFilter.tint(lerpColor(Ink, Color(0xFF0B0D14), k)))
    }
    Spacer(Modifier.height(6.dp))
    T(label, 12.sp, Modifier.graphicsLayer { alpha = 0.35f + 0.65f * k }, weight = FontWeight.Medium)
  }
}

// ---------------------------------------------------------------- Overlay menu (focus-trapped list)

data class MenuItem(val label: String, val icon: ImageVector? = null, val sub: String? = null, val action: () -> Unit)

@Composable
fun OverlayMenu(title: String, subtitle: String?, header: (@Composable () -> Unit)?, items: List<MenuItem>, onDismiss: () -> Unit) {
  var sel by remember { mutableIntStateOf(0) }
  var sawDown by remember { mutableStateOf(false) }
  val fr = remember { FocusRequester() }
  LaunchedEffect(Unit) { tryFocus(fr) }
  Box(
    Modifier.fillMaxSize().background(Color(0xA6000000))
      .focusRequester(fr)
      .onKeyEvent { e ->
        val k = e.nativeKeyEvent; val down = k.action == NKey.ACTION_DOWN
        when (k.keyCode) {
          NKey.KEYCODE_DPAD_UP -> { if (down) sel = (sel - 1).coerceAtLeast(0); true }
          NKey.KEYCODE_DPAD_DOWN -> { if (down) sel = (sel + 1).coerceAtMost(items.lastIndex); true }
          NKey.KEYCODE_DPAD_LEFT, NKey.KEYCODE_DPAD_RIGHT, NKey.KEYCODE_MENU -> true
          NKey.KEYCODE_DPAD_CENTER, NKey.KEYCODE_ENTER, NKey.KEYCODE_NUMPAD_ENTER, NKey.KEYCODE_BUTTON_A -> {
            if (down && k.repeatCount == 0) sawDown = true
            if (!down && sawDown) { sawDown = false; items.getOrNull(sel)?.action?.invoke() }
            true
          }
          NKey.KEYCODE_BACK, NKey.KEYCODE_ESCAPE -> { if (!down) onDismiss(); true }
          else -> false
        }
      }
      .focusable(),
    Alignment.Center,
  ) {
    Column(Modifier.width(460.dp).glass(RoundedCornerShape(30.dp), fill = Color(0xE6161923), edge = Color(0x40FFFFFF)).padding(26.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        header?.invoke()
        if (header != null) Spacer(Modifier.width(18.dp))
        Column {
          T(title, 24.sp, weight = FontWeight.Medium)
          if (subtitle != null) T(subtitle, 13.sp, color = Ink.copy(alpha = 0.55f))
        }
      }
      Spacer(Modifier.height(18.dp))
      items.forEachIndexed { i, it ->
        val on = i == sel
        val k by animateFloatAsState(if (on) 1f else 0f, tween(150), label = "mi")
        Row(
          Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(lerpColor(Color(0x00FFFFFF), Color(0xF2FFFFFF), k))
            .pointerInput(i) { detectTapGestures { _ -> sel = i; it.action() } }
            .padding(horizontal = 18.dp, vertical = 13.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (it.icon != null) {
            Image(androidx.compose.ui.graphics.vector.rememberVectorPainter(it.icon), null, Modifier.size(22.dp),
              colorFilter = ColorFilter.tint(lerpColor(Ink.copy(alpha = 0.8f), Color(0xFF0B0D14), k)))
            Spacer(Modifier.width(16.dp))
          }
          T(it.label, 17.sp, color = lerpColor(Ink, Color(0xFF0B0D14), k), weight = FontWeight.Medium)
        }
      }
    }
  }
}

@Composable
fun AppThumb(app: AppEntry, w: Dp) {
  Box(Modifier.size(w, w * 9f / 16f).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(lighten(app.tint, 0.15f), darken(app.tint, 0.35f)))), Alignment.Center) {
    if (app.banner != null) Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else UniformIcon(app, w * 0.34f)
  }
}

/** Thin ring used for the ambient "exit" hint. */
@Composable
fun Hairline(modifier: Modifier) = Canvas(modifier) { drawRoundRect(Color.White.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(size.height / 2)) }
