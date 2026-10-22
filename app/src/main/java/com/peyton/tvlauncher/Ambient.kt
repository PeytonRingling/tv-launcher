package com.peyton.tvlauncher

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Where the ambient screen gets photos and their analysis (MainActivity, the screensaver, tests). Blocking calls. */
interface AmbientSource {
  fun photoMeta(p: Photo): PhotoMeta?
  /** Decoded so it still covers [w]x[h] pixels. */
  fun loadCover(p: Photo, w: Int, h: Int): Bitmap?
}

private const val SLIDE_MS = 16_000
private const val FADE_MS = 2_600

/** One prepared slide: bitmaps decoded and crops computed off the main thread; drawing never allocates. */
private class Shown(val id: Int, val spec: SlideSpec, val imgs: List<ImageBitmap>, val crops: List<NRect>, val focus: NRect?,
                    val blur: ImageBitmap?, val filters: List<ColorFilter?>, val zoomIn: Boolean)

private val darken55 = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.45f, 0f, 0f, 0f, 0f, 0f, 0.45f, 0f, 0f, 0f, 0f, 0f, 0.45f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

private fun prepare(src: AmbientSource, spec: SlideSpec, id: Int, screenW: Int, screenH: Int, enhance: Boolean): Shown? {
  fun meta(p: Photo, b: Bitmap) = src.photoMeta(p) ?: run {   // not analysed yet: plain centre crop, no faces
    val (c, f) = Crop.fit(b.width, b.height, emptyList(), 0.5f, 0.45f, 16f / 9f)
    PhotoMeta(b.width, b.height, emptyList(), 0.5f, 0.45f, c, f, 100f, null, 0f, 1f, 0.5f)
  }
  fun filt(m: PhotoMeta) = if (enhance) Enhance.matrix(m)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) } else null
  val zoomIn = Random.nextBoolean()
  return when (spec) {
    is SlideSpec.Full -> {
      val b = src.loadCover(spec.p, (screenW * 1.16f).toInt(), (screenH * 1.16f).toInt()) ?: return null
      val m = meta(spec.p, b)
      val crop = m.crop
      Shown(id, spec, listOf(b.asImageBitmap()), listOf(crop), m.faces.takeIf { it.isNotEmpty() }?.let { NRect.union(it.map { f -> f.within(crop) }) }
        ?: NRect(m.subjectX - 0.1f, m.subjectY - 0.1f, m.subjectX + 0.1f, m.subjectY + 0.1f).within(crop), null, listOf(filt(m)), zoomIn)
    }
    is SlideSpec.Contain -> {
      val b0 = src.loadCover(spec.p, (screenH * 0.75f).toInt(), (screenH * 1.06f).toInt()) ?: return null
      val m = meta(spec.p, b0)
      // Faces mapped from image space into the screen frame where the photo is drawn (height 94%, centred).
      val h = screenH * 0.94f; val w = h * b0.width / b0.height; val ox = (screenW - w) / 2; val oy = (screenH - h) / 2
      val focus = NRect.union(m.faces)?.let { f -> NRect((ox + f.l * w) / screenW, (oy + f.t * h) / screenH, (ox + f.r * w) / screenW, (oy + f.b * h) / screenH) }
      Shown(id, spec, listOf(b0.asImageBitmap()), listOf(NRect.FULL), focus, Photos.blurFill(b0).asImageBitmap(), listOf(filt(m)), zoomIn)
    }
    is SlideSpec.Pair2 -> {
      val (pw, ph) = pairPanel(screenW, screenH)
      val a = src.loadCover(spec.a, pw.toInt(), ph.toInt()) ?: return null
      val b = src.loadCover(spec.b, pw.toInt(), ph.toInt()) ?: return null
      val ma = meta(spec.a, a); val mb = meta(spec.b, b)
      val ca = Crop.fit(a.width, a.height, ma.faces, ma.subjectX, ma.subjectY, pw / ph).first
      val cb = Crop.fit(b.width, b.height, mb.faces, mb.subjectX, mb.subjectY, pw / ph).first
      Shown(id, spec, listOf(a.asImageBitmap(), b.asImageBitmap()), listOf(ca, cb), null, Photos.blurFill(a).asImageBitmap(), listOf(filt(ma), filt(mb)), zoomIn)
    }
  }
}

/** Panel size (px) for two portraits side by side in the 16:9 frame: 3.4% margins, 2.4% gap. */
fun pairPanel(w: Int, h: Int): Pair<Float, Float> { val m = h * 0.034f; val gap = w * 0.024f; return ((w - 2 * m - gap) / 2) to (h - 2 * m) }

@Composable
fun AmbientScreen(photos: List<Photo>, src: AmbientSource, weather: Weather?, use24h: Boolean, celsius: Boolean, clock: ClockLook? = null, enhance: Boolean = false, place: String = "") {
  var slides by remember { mutableStateOf(listOf<Shown>()) }
  BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
    val sw = constraints.maxWidth; val sh = constraints.maxHeight
    LaunchedEffect(photos, enhance, sw, sh) {
      if (photos.isEmpty() || sw <= 0) return@LaunchedEffect
      var id = 0; var misses = 0
      while (true) {
        val plan = withContext(Dispatchers.IO) { AmbientPlan.build(photos.shuffled().map { it to src.photoMeta(it) }) }
        if (plan.isEmpty()) break
        for (spec in plan) {
          val s = withContext(Dispatchers.Default) { try { prepare(src, spec, id++, sw, sh, enhance) } catch (_: Throwable) { null } }
          if (s == null) { if (++misses > plan.size * 2 && slides.isEmpty()) return@LaunchedEffect; continue }
          slides = (slides + s).takeLast(2)
          delay((SLIDE_MS - FADE_MS).toLong())
        }
      }
    }
    slides.forEach { s -> key(s.id) { SlideView(s) } }
    AmbientOverlay(weather, use24h, celsius, clock, place)
  }
}

@Composable
private fun SlideView(s: Shown) {
  val fade = remember { Animatable(0f) }
  val p = remember { Animatable(0f) }
  LaunchedEffect(s.id) {
    launch { fade.animateTo(1f, tween(FADE_MS)) }
    p.animateTo(1f, tween(SLIDE_MS + FADE_MS * 2, easing = LinearEasing))
  }
  val d = LocalDensity.current
  val radius = with(d) { 18.dp.toPx() }
  // Each slide is one layer: the draw lambda reads no animation state (recorded once); only the layer transform moves.
  Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = fade.value }) {
    when (s.spec) {
      is SlideSpec.Full -> Canvas(Modifier.fillMaxSize().graphicsLayer {
        val (sc, tx, ty) = Crop.kenBurns(p.value, s.focus, s.zoomIn)
        scaleX = sc; scaleY = sc; translationX = tx * size.width; translationY = ty * size.height
      }) { drawCrop(s.imgs[0], s.crops[0], Offset.Zero, size, s.filters[0]) }
      is SlideSpec.Contain -> {
        Canvas(Modifier.fillMaxSize().graphicsLayer { val sc = 1.12f + 0.04f * p.value; scaleX = sc; scaleY = sc }) {
          s.blur?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), colorFilter = darken55, filterQuality = FilterQuality.Low) }
        }
        val img = s.imgs[0]
        Canvas(Modifier.fillMaxSize().graphicsLayer {
          // Gentle zoom (≤4%) toward faces; the whole photo stays visible except a sliver, faces never leave the window.
          val (sc, tx, ty) = Crop.kenBurns(p.value, s.focus, s.zoomIn, maxZoom = 1.04f)
          // The panel itself must stay on screen (with a small margin): pan only as far as the zoom allows.
          val ph = 0.94f * sc; val pw = ph * size.height * img.width / img.height / size.width * 1f
          val my = ((1f - ph) / 2f - 0.008f).coerceAtLeast(0f); val mx = ((1f - pw) / 2f - 0.008f).coerceAtLeast(0f)
          scaleX = sc; scaleY = sc; translationX = tx.coerceIn(-mx, mx) * size.width; translationY = ty.coerceIn(-my, my) * size.height
        }) {
          val h = size.height * 0.94f; val w = h * img.width / img.height
          val o = Offset((size.width - w) / 2, (size.height - h) / 2)
          for (i in 6 downTo 1) drawRoundRect(Color.Black, o - Offset(i * 4f, i * 2f), Size(w + i * 8f, h + i * 8f), CornerRadius(radius + i * 4f), alpha = 0.06f)
          clipPath(Path().apply { addRoundRect(RoundRect(o.x, o.y, o.x + w, o.y + h, CornerRadius(radius))) }) { drawCrop(img, NRect.FULL, o, Size(w, h), s.filters[0]) }
        }
      }
      is SlideSpec.Pair2 -> {
        Canvas(Modifier.fillMaxSize().graphicsLayer { val sc = 1.12f + 0.04f * p.value; scaleX = sc; scaleY = sc }) {
          s.blur?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), colorFilter = darken55, filterQuality = FilterQuality.Low) }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { val sc = 1f + 0.03f * (if (s.zoomIn) p.value else 1f - p.value); scaleX = sc; scaleY = sc }) {
          val (pw, ph) = pairPanel(size.width.toInt(), size.height.toInt())
          val m = size.height * 0.034f
          for (i in 0..1) {
            val o = Offset(m + i * (pw + size.width * 0.024f), m)
            for (k in 6 downTo 1) drawRoundRect(Color.Black, o - Offset(k * 3f, k * 1.5f), Size(pw + k * 6f, ph + k * 6f), CornerRadius(radius + k * 3f), alpha = 0.06f)
            clipPath(Path().apply { addRoundRect(RoundRect(o.x, o.y, o.x + pw, o.y + ph, CornerRadius(radius))) }) { drawCrop(s.imgs[i], s.crops[i], o, Size(pw, ph), s.filters[i]) }
          }
        }
      }
    }
  }
}

private fun DrawScope.drawCrop(img: ImageBitmap, c: NRect, at: Offset, sz: Size, filter: ColorFilter?) {
  drawImage(img, IntOffset((c.l * img.width).toInt(), (c.t * img.height).toInt()), IntSize((c.w * img.width).toInt().coerceAtLeast(1), (c.h * img.height).toInt().coerceAtLeast(1)),
    IntOffset(at.x.toInt(), at.y.toInt()), IntSize(sz.width.toInt(), sz.height.toInt()), colorFilter = filter, filterQuality = FilterQuality.High)
}

@Composable
private fun AmbientOverlay(weather: Weather?, use24h: Boolean, celsius: Boolean, clock: ClockLook?, place: String) {
  val now = rememberNow()
  val minute = now / 60_000          // burn-in protection: nudge a few dp every minute
  val nudgeX = ((minute * 37) % 17 - 8).toInt().dp
  val nudgeY = ((minute * 53) % 11 - 5).toInt().dp
  var hint by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) { delay(4500); hint = false }
  val hintA by animateFloatAsState(if (hint) 1f else 0f, tween(900), label = "h")
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 64.dp + nudgeX, end = 64.dp - nudgeX, bottom = 52.dp + nudgeY), verticalAlignment = Alignment.Bottom) {
      if (clock != null) ClockFace(clock, use24h, scale = if (clock.style == ClockStyle.CORNER) 1.4f else 0.8f, align = Alignment.Start)
      else Column {
        Row(verticalAlignment = Alignment.Bottom) {
          T(fmtTime(now, use24h), 112.sp, weight = FontWeight.Thin, spacing = (-4).sp)
          if (!use24h) T(fmtAmPm(now), 22.sp, Modifier.padding(start = 8.dp, bottom = 24.dp), weight = FontWeight.Light, color = Ink.copy(alpha = 0.7f))
        }
        T(fmtDate(now), 22.sp, Modifier.padding(start = 6.dp), weight = FontWeight.Light, color = Ink.copy(alpha = 0.8f), spacing = 0.5.sp)
      }
      Spacer(Modifier.weight(1f))
      if (weather != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        WeatherGlyph(WeatherApi.sky(weather.code), weather.isDay, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column {
          T(WeatherApi.fmt(weather.tempF, celsius), 46.sp, weight = FontWeight.ExtraLight)
          T(if (place.isEmpty()) WeatherApi.describe(weather.code) else "${WeatherApi.describe(weather.code)}  ·  $place", 15.sp, color = Ink.copy(alpha = 0.75f))
        }
      }
    }
    Box(Modifier.align(Alignment.TopEnd).padding(36.dp).graphicsLayer { alpha = hintA }.glass(RoundedCornerShape(50), fill = Color(0x33000000)).padding(horizontal = 18.dp, vertical = 8.dp)) {
      T("Press any button to return", 13.sp, color = Ink.copy(alpha = 0.8f))
    }
  }
}
