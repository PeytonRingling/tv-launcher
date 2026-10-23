package com.peyton.tvlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ================================================================ 4) Neon synthwave

private val NeonPalette = listOf(Color(0xFF2DE2FF), Color(0xFFFF3DCB), Color(0xFFFFE14D), Color(0xFF5CFF8A), Color(0xFFFF8A3D), Color(0xFFB46BFF))
private val NeonPink = Color(0xFFFF3DCB)
private val NeonCyan = Color(0xFF2DE2FF)
private const val HORIZON = 0.565f
private val SignW = 136.dp
private val SignH = 86.dp
private val SignGap = 22.dp

/** Tube ignition: flickers on as [p] goes 0→1 (deterministic per [seed], so it's the same every frame for the same p). */
fun neonFlicker(p: Float, seed: Int): Float {
  if (p <= 0f) return 0f; if (p >= 1f) return 1f
  val a = 0.15f + 0.25f * hash01(seed, 1); val b = a + 0.08f + 0.1f * hash01(seed, 2); val c = b + 0.06f + 0.12f * hash01(seed, 3)
  return when { p < a -> 0f; p < a + 0.05f -> 0.85f; p < b -> 0.08f; p < b + 0.04f -> 0.6f; p < c -> 0.15f; else -> ((p - c) / (1f - c)).coerceIn(0f, 1f) * 0.3f + 0.7f }
}

@Composable
fun NeonHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.NEON)) }
  val drv = rememberFocusDriver(items.size)
  val fx = rememberStyleFx(env)
  val t = rememberAmbientTime(env, 30)
  val nav = remember(items.size) { StyleNav(items.size, items.size) }
  VerseFums(st, env.actions)
  val now = rememberNow()

  Box(Modifier.fillMaxSize().background(Color(0xFF0B0221)).testTag("style-neon")) {
    // ---- sky: baked with an ordered dither so the long sunset gradient never bands on an 8-bit panel
    Box(Modifier.fillMaxSize().drawWithCache {
      val w = 480; val h = (270 * HORIZON).toInt() + 2
      val stops = floatArrayOf(0f, 0.38f, 0.66f, 0.86f, 1f)
      val cols = listOf(Color(0xFF0B0221), Color(0xFF2A0845), Color(0xFF6A0F6E), Color(0xFFD8336A), Color(0xFFFF9A44))
      val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
      val sky = Baked.get("neon-sky", w, h) { x, y ->
        val f = y / (h - 1f); var j = 0; while (j < stops.size - 2 && f > stops[j + 1]) j++
        val u = ((f - stops[j]) / (stops[j + 1] - stops[j])).coerceIn(0f, 1f); val c = lerpColor(cols[j], cols[j + 1], u)
        val d = (bayer[(y and 3) * 4 + (x and 3)] / 16f - 0.47f) / 255f
        Baked.argb(1f, c.red + d, c.green + d, c.blue + d)
      }
      val starsX = FloatArray(70) { hash01(it, 11) }; val starsY = FloatArray(70) { hash01(it, 12) * hash01(it, 13) * 0.85f }
      onDrawBehind {
        drawImage(sky, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), (size.height * HORIZON).toInt() + 4), filterQuality = FilterQuality.Low)
        val tt = t.value
        for (i in starsX.indices) {
          val a = (0.35f + 0.45f * kotlin.math.sin(tt * (0.6f + hash01(i, 14) * 1.6f) + i)).coerceIn(0f, 1f) * (1f - starsY[i] / 0.85f)
          drawCircle(Color.White, (0.6f + hash01(i, 15) * 1.1f).dp.toPx(), Offset(starsX[i] * size.width, starsY[i] * size.height * HORIZON), alpha = a)
        }
      }
    })
    val horizonY = { h: Float -> h * HORIZON }
    // ---- sun (offscreen layer so its stripes can cut real gaps) + its glow
    NeonSun(t, Modifier.align(Alignment.TopCenter).offset(y = (540 * HORIZON - 228).dp), reflect = false)
    // ---- mountains (static)
    Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
      val hy = horizonY(size.height)
      fun ridge(seed: Int, amp: Float, base: Float): Path = Path().apply {
        moveTo(0f, hy); val n = 28
        for (i in 0..n) { val x = size.width * i / n; val v = vnoise(i * 0.55f, 0f, seed) * 0.7f + vnoise(i * 1.7f, 2f, seed) * 0.3f; lineTo(x, hy - base - amp * v) }
        lineTo(size.width, hy); close()
      }
      val far = ridge(21, 70.dp.toPx(), 4.dp.toPx()); val near = ridge(22, 46.dp.toPx(), 0f)
      val rim = Stroke(1.5.dp.toPx())
      onDrawBehind {
        drawPath(far, Color(0xFF3A0B5E)); drawPath(far, NeonPink, alpha = 0.55f, style = rim)
        drawPath(near, Color(0xFF1C0436)); drawPath(near, NeonCyan, alpha = 0.45f, style = rim)
      }
    })
    // ---- the floor: a perspective grid scrolling toward you, with the sun reflected in it
    Box(Modifier.fillMaxSize().drawWithCache {
      val hy = horizonY(size.height); val fh = size.height - hy
      val floor = Brush.verticalGradient(listOf(Color(0xFF2B0040), Color(0xFF12001F)), startY = hy, endY = size.height)
      val wide = 5.dp.toPx(); val thin = 1.4.dp.toPx()
      onDrawBehind {
        drawRect(floor, Offset(0f, hy), Size(size.width, fh))
        val e = StyleDecel.transform((fx.enter.value / 0.6f).coerceIn(0f, 1f))
        val speed = 0.45f + 2.6f * StyleAccel.transform(fx.launch.value)
        val phase = (t.value * speed) % 1f
        val cx = size.width / 2
        clipRect(top = hy, bottom = hy + fh * e) {
          for (j in 0..16) {   // horizontal lines
            val depth = j - phase
            if (depth < 0f) continue
            val y = hy + fh / (1f + depth * 0.42f)
            val a = (1f - (y - hy).let { 1f - it / fh }).coerceIn(0f, 1f)
            drawLine(NeonPink, Offset(0f, y), Offset(size.width, y), wide, alpha = 0.10f * a)
            drawLine(NeonPink, Offset(0f, y), Offset(size.width, y), thin, alpha = 0.25f + 0.6f * a)
          }
          for (i in -14..14) {   // verticals converge on the vanishing point
            val xb = cx + i * size.width * 0.11f
            drawLine(NeonPink, Offset(cx + i * 6.dp.toPx(), hy), Offset(xb, size.height), wide, alpha = 0.08f)
            drawLine(NeonPink, Offset(cx + i * 6.dp.toPx(), hy), Offset(xb, size.height), thin, alpha = 0.55f)
          }
        }
        // horizon haze
        drawRect(Brush.verticalGradient(listOf(Color(0x00FF3DCB), Color(0x55FF3DCB), Color(0x00FF3DCB)), startY = hy - 14.dp.toPx(), endY = hy + 18.dp.toPx()),
          Offset(0f, hy - 14.dp.toPx()), Size(size.width, 32.dp.toPx()))
      }
    })
    NeonSun(t, Modifier.align(Alignment.TopCenter).offset(y = (540 * HORIZON).dp), reflect = true)

    // ---- clock: neon tubes that ignite (flicker) on first show
    val time = fmtTime(now, st.use24h)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Row {
        time.forEachIndexed { ci, ch ->
          Box(Modifier.graphicsLayer { alpha = neonFlicker((fx.enter.value - 0.05f) / 0.8f, ci + 3) }) {
            T(ch.toString(), 96.sp, color = Color(0xFFFFE3F7), family = Monoton, shadow = Shadow(NeonPink, Offset.Zero, 34f))
          }
        }
      }
      Box(Modifier.graphicsLayer { alpha = neonFlicker((fx.enter.value - 0.2f) / 0.75f, 40) }) {
        T(java.text.SimpleDateFormat("EEEE  ·  MMMM d", java.util.Locale.US).format(java.util.Date(now)).uppercase(), 15.sp, color = Color(0xFFD9FBFF),
          weight = FontWeight.Bold, spacing = 4.sp, shadow = Shadow(NeonCyan, Offset.Zero, 18f))
      }
    }
    // ---- weather (left) and verse (right): small cyan signage
    Column(Modifier.padding(start = 56.dp, top = 44.dp).width(220.dp).graphicsLayer { alpha = neonFlicker((fx.enter.value - 0.3f) / 0.7f, 50) }) {
      val w = st.weather
      T(w?.let { WeatherApi.fmt(it.tempF, st.celsius) } ?: "--°", 44.sp, color = Color(0xFFFFF4C2), family = Monoton, shadow = Shadow(Color(0xFFFFB13D), Offset.Zero, 24f))
      T((w?.let { WeatherApi.describe(it.code) } ?: if (st.place == null) "Set a ZIP in Settings" else "Forecast loading").uppercase(), 12.sp, color = Color(0xFFD9FBFF),
        weight = FontWeight.Bold, spacing = 2.sp, shadow = Shadow(NeonCyan, Offset.Zero, 14f), maxLines = 2)
      StyleData.place(st)?.let { T(it.uppercase(), 10.sp, color = NeonCyan.copy(alpha = 0.8f), weight = FontWeight.Bold, spacing = 2.sp, shadow = Shadow(NeonCyan, Offset.Zero, 10f)) }
    }
    st.verse?.let { v ->
      Column(Modifier.align(Alignment.TopEnd).padding(end = 56.dp, top = 48.dp).width(240.dp).graphicsLayer { alpha = neonFlicker((fx.enter.value - 0.35f) / 0.65f, 60) },
        horizontalAlignment = Alignment.End) {
        T(v.text, if (v.text.length > 140) 11.5.sp else 13.sp, color = Color(0xFFFFE3F7), weight = FontWeight.Medium, align = androidx.compose.ui.text.style.TextAlign.End,
          shadow = Shadow(NeonPink, Offset.Zero, 12f), maxLines = 7)
        T(v.reference.uppercase(), 11.sp, Modifier.padding(top = 4.dp), color = NeonCyan, weight = FontWeight.Bold, spacing = 2.sp, shadow = Shadow(NeonCyan, Offset.Zero, 12f))
        StyleData.notice(st)?.let { T(it, 8.5.sp, color = Color(0xFFD9FBFF).copy(alpha = 0.7f), align = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2) }
      }
    }

    // ---- the signs (apps), centered on focus, sitting on the floor just below the horizon
    Box(Modifier.align(Alignment.TopStart).padding(top = (540 * HORIZON + 26).dp).fillMaxWidth().height(SignH + 60.dp)) {
      Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(top = 10.dp).graphicsLayer {
        val stride = (SignW + SignGap).toPx()
        translationX = 480.dp.toPx() - SignW.toPx() / 2 - drv.pos.value * stride
      }, horizontalArrangement = Arrangement.spacedBy(SignGap)) {
        items.forEachIndexed { i, item -> NeonSign(env, items, i, item, drv, fx, nav) }
      }
    }
    // ---- launch: white-hot flare, then lights out
    Box(Modifier.fillMaxSize().drawBehind {
      val l = fx.launch.value; if (l <= 0f) return@drawBehind
      val c = Offset(size.width / 2, (540 * HORIZON + 36 + 43).dp.toPx())
      drawCircle(Brush.radialGradient(listOf(Color.White, NeonPalette[fx.launching.coerceAtLeast(0) % NeonPalette.size], Color.Transparent), c, size.width * (0.1f + 0.9f * l)),
        size.width * (0.1f + 0.9f * l), c, alpha = (l * 2f).coerceAtMost(1f) * (1f - ((l - 0.75f) / 0.25f).coerceIn(0f, 1f)))
      drawRect(Color.Black, alpha = ((l - 0.6f) / 0.4f).coerceIn(0f, 1f))
    })
  }
}

/** Sun radius: its top stays well clear of the date line under the clock. */
private val SunR = 98.dp

@Composable
private fun NeonSun(t: State<Float>, modifier: Modifier, reflect: Boolean) {
  Box(modifier.size(456.dp, 228.dp).graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
    if (reflect) { scaleY = -0.42f; transformOrigin = TransformOrigin(0.5f, 0f); alpha = 0.32f }
  }.drawWithCache {
    val r = SunR.toPx(); val c = Offset(size.width / 2, size.height)
    val disc = Brush.verticalGradient(listOf(Color(0xFFFFF07A), Color(0xFFFFB347), Color(0xFFFF4F8B), Color(0xFFE0247E)), startY = c.y - r, endY = c.y)
    val glow = Brush.radialGradient(
      0f to Color(0x66FF6A8A), 0.35f to Color(0x40FF4F8B), 0.6f to Color(0x1AFF3DCB), 0.8f to Color(0x08FF3DCB), 1f to Color.Transparent, center = c, radius = size.width / 2)
    onDrawBehind {
      drawRect(glow)
      drawCircle(disc, r, c)
      // stripes drift down through the lower half and thicken toward the horizon
      val ph = (t.value * 0.18f) % 1f
      for (j in 0..7) {
        val f = (j + ph) / 8f
        val y = c.y - r * 0.62f + f * r * 0.62f
        val h = 1.2.dp.toPx() + f * 9.dp.toPx()
        drawRect(Color.Black, Offset(c.x - r - 2f, y), Size(2 * r + 4f, h), blendMode = BlendMode.DstOut)
      }
      if (reflect) for (j in 0..10) drawRect(Color.Black, Offset(c.x - r - 2f, size.height - j * 9.dp.toPx()), Size(2 * r + 4f, 3.dp.toPx()), alpha = 0.6f, blendMode = BlendMode.DstOut)
    }
  })
}

@Composable
private fun NeonSign(env: HomeEnv, items: List<StyleItem>, i: Int, item: StyleItem, drv: FocusDriver, fx: StyleFx, nav: StyleNav) {
  val neon = NeonPalette[i % NeonPalette.size]
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(SignW, SignH)
      .graphicsLayer {
        val k = drv.k(i); val s = 1f + 0.10f * k; scaleX = s; scaleY = s; translationY = -8.dp.toPx() * k
        alpha = neonFlicker(stagger(fx.enter.value, 0.25f, 0.03f, i, 0.6f), 100 + i).coerceAtLeast(0.02f)
      }
      .drawWithCache {
        val m = 20.dp.toPx().toInt(); val r = 16.dp.toPx()
        val halo = SoftGlow.custom(size.width.toInt(), size.height.toInt(), m, r, 0.75f)
        val tint = ColorFilter.tint(neon, BlendMode.SrcIn)
        val tube = Stroke(2.6.dp.toPx()); val core = Stroke(1.dp.toPx())
        onDrawBehind {
          val k = drv.k(i)
          // tube ignition flicker follows the same focus progress (no separate timer)
          val lit = 0.30f + 0.70f * neonFlicker(k, i * 7 + 1)
          if (k > 0.004f) drawImage(halo, Offset(-m.toFloat(), -m.toFloat()), alpha = k * lit, colorFilter = tint)
          drawRoundRect(Color(0xCC0D0320), cornerRadius = CornerRadius(r))
          drawRoundRect(neon, cornerRadius = CornerRadius(r), style = tube, alpha = lit)
          drawRoundRect(Color.White, cornerRadius = CornerRadius(r), style = core, alpha = (lit - 0.3f).coerceAtLeast(0f) * k)
        }
      }
      .styleItem(env, items, i, drv, fx, nav), Alignment.Center) {
      ItemArt(item, Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).graphicsLayer { alpha = 0.66f + 0.34f * drv.k(i) })
    }
    Box(Modifier.padding(top = 10.dp).graphicsLayer { alpha = 0.62f + 0.38f * drv.k(i) }) {
      T(item.label, 15.sp, color = lighten(neon, 0.55f), weight = FontWeight.Bold, spacing = 1.sp, shadow = Shadow(neon, Offset.Zero, 16f))
    }
  }
}
