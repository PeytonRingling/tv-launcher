package com.peyton.tvlauncher

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ================================================================ 5) Space: the clock is the sun, apps orbit it

private const val PLANET_PX = 176

/** Each app pre-rendered once as a lit sphere (its tile wrapped in a disc, terminator shading, rim light). */
private object Planets {
  private val cache = HashMap<String, ImageBitmap>()
  fun of(item: StyleItem): ImageBitmap = synchronized(cache) {
    cache.getOrPut(item.key + (item.app?.tile?.hashCode() ?: 0)) {
      val s = PLANET_PX; val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
      val c = android.graphics.Canvas(b); val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
      val clip = android.graphics.Path().apply { addCircle(s / 2f, s / 2f, s / 2f - 1f, android.graphics.Path.Direction.CW) }
      c.save(); c.clipPath(clip)
      val tint = item.tint.toArgb()
      p.shader = android.graphics.LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), lighten(item.tint, 0.2f).toArgb(), darken(item.tint, 0.4f).toArgb(), android.graphics.Shader.TileMode.CLAMP)
      c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); p.shader = null
      val tile = item.app?.tile?.asAndroidBitmap()
      if (tile != null) c.drawBitmap(tile, null, android.graphics.RectF(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f), p)
      else { p.color = android.graphics.Color.WHITE; val d = s / 9f
        if (item.kind == StyleItem.Kind.ALL) for (a in 0..2) for (bb in 0..2) c.drawCircle(s * 0.5f + (a - 1) * d * 1.6f, s * 0.5f + (bb - 1) * d * 1.6f, d * 0.42f, p)
        else { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = d * 0.7f; c.drawCircle(s / 2f, s / 2f, d * 1.6f, p); p.style = android.graphics.Paint.Style.FILL
          for (k in 0 until 8) { val a = k * PI / 4; c.drawCircle((s / 2f + cos(a) * d * 2.5f).toFloat(), (s / 2f + sin(a) * d * 2.5f).toFloat(), d * 0.55f, p) } } }
      // sphere shading: lit from the sun (above), terminator falls away below-right
      p.shader = android.graphics.RadialGradient(s * 0.38f, s * 0.28f, s * 0.82f, intArrayOf(0x00000000, 0x22000000, 0x99000000.toInt(), 0xE6000000.toInt()),
        floatArrayOf(0f, 0.45f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
      c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
      p.shader = android.graphics.RadialGradient(s * 0.34f, s * 0.24f, s * 0.3f, intArrayOf(0x55FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
      c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); p.shader = null
      c.restore()
      p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f; p.color = (tint and 0x00FFFFFF) or 0x88000000.toInt()
      c.drawCircle(s / 2f, s / 2f, s / 2f - 2f, p)
      b.asImageBitmap()
    }
  }
}

@Composable
fun SpaceHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.SPACE)) }
  val n = items.size
  val drv = rememberFocusDriver(n, wrap = true)
  val fx = rememberStyleFx(env)
  val t = rememberAmbientTime(env, 30)
  val nav = remember(n) { StyleNav(n, n, wrap = true) }
  VerseFums(st, env.actions)
  val now = rememberNow()
  val planets = remember(items) { items.map { Planets.of(it) } }

  Box(Modifier.fillMaxSize().background(Color(0xFF02030A)).testTag("style-space")) {
    // ---------------- starfield: three depth layers, twinkle, drift + parallax with the orbit; warp streaks on launch/entry
    Box(Modifier.fillMaxSize().drawWithCache {
      val layers = listOf(110 to 0.4f, 60 to 0.8f, 26 to 1.5f)
      val xs = layers.mapIndexed { li, (c, _) -> FloatArray(c) { hash01(it, 30 + li) } }
      val ys = layers.mapIndexed { li, (c, _) -> FloatArray(c) { hash01(it, 40 + li) } }
      val neb1 = Brush.radialGradient(listOf(Color(0x332A3BFF), Color(0x142A3BFF), Color.Transparent), Offset(size.width * 0.18f, size.height * 0.3f), size.width * 0.45f)
      val neb2 = Brush.radialGradient(listOf(Color(0x2EC23BFF), Color(0x10C23BFF), Color.Transparent), Offset(size.width * 0.86f, size.height * 0.75f), size.width * 0.4f)
      onDrawBehind {
        drawRect(neb1); drawRect(neb2)
        val tt = t.value; val rot = drv.pos.value / n.coerceAtLeast(1)
        val warp = maxOf(1f - StyleDecel.transform(fx.enter.value.coerceIn(0f, 1f)), StyleAccel.transform(fx.launch.value))
        val cx = size.width / 2; val cy = size.height * 0.46f
        layers.forEachIndexed { li, (count, depth) ->
          val x0 = xs[li]; val y0 = ys[li]
          for (i in 0 until count) {
            var x = (x0[i] + tt * 0.004f * depth - rot * 0.06f * depth) % 1f; if (x < 0) x += 1f
            val px = x * size.width; val py = y0[i] * size.height
            val tw = 0.55f + 0.45f * sin(tt * (0.8f + hash01(i, 50 + li) * 2.2f) + i * 1.7f)
            val r = (0.5f + depth * 0.55f).dp.toPx()
            if (warp > 0.02f) {
              val dx = px - cx; val dy = py - cy
              drawLine(Color.White, Offset(px, py), Offset(px - dx * warp * 0.35f * depth, py - dy * warp * 0.35f * depth), r * 1.2f, alpha = 0.7f * tw)
            } else drawCircle(Color.White, r, Offset(px, py), alpha = (0.25f + 0.6f * tw) * (0.5f + depth / 3f).coerceAtMost(1f))
          }
        }
      }
    })
    // ---------------- sun, orbit, planets in one canvas (z-sorted every frame without allocating)
    Box(Modifier.fillMaxSize().drawWithCache {
      val cx = size.width / 2; val cy = 238.dp.toPx()
      val sunR = 74.dp.toPx(); val rx = 372.dp.toPx(); val ry = 104.dp.toPx(); val oy = cy + 26.dp.toPx()
      val surface = Baked.get("sun-surface", 192, 192) { x, y ->
        val u = x / 96f - 1f; val v = y / 96f - 1f; val d = kotlin.math.sqrt(u * u + v * v)
        if (d > 1f) 0 else { val g = vnoise(x / 9f, y / 9f, 77) * 0.6f + vnoise(x / 3.5f, y / 3.5f, 78) * 0.4f
          val limb = 1f - d * d * 0.45f
          Baked.argb(1f, 1f * limb, (0.55f + 0.35f * g) * limb, (0.12f + 0.25f * g * g) * limb) }
      }
      val corona = Brush.radialGradient(0f to Color(0xCCFFC861), 0.25f to Color(0x66FF9A3D), 0.5f to Color(0x26FF7A1A), 0.75f to Color(0x0AFF6A00), 1f to Color.Transparent,
        center = Offset(cx, cy), radius = sunR * 3.6f)
      val streak = Brush.horizontalGradient(listOf(Color.Transparent, Color(0x2EFFD9A0), Color.Transparent), startX = cx - size.width * 0.3f, endX = cx + size.width * 0.3f)
      val ringBack = Stroke(1.2.dp.toPx()); val trail = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
      val order = IntArray(n); val depth = FloatArray(n); val px = FloatArray(n); val py = FloatArray(n); val pr = FloatArray(n)
      val planetBase = 30.dp.toPx(); val planetFocus = 22.dp.toPx()
      onDrawBehind {
        val e = StyleDecel.transform((fx.enter.value / 0.8f).coerceIn(0f, 1f))
        val orbitScale = 0.25f + 0.75f * e
        for (i in 0 until n) {
          val th = PI / 2 + 2 * PI * drv.ringOffset(i, n) / n
          px[i] = cx + (rx * orbitScale * cos(th)).toFloat(); py[i] = oy + (ry * orbitScale * sin(th)).toFloat()
          depth[i] = sin(th).toFloat()
          val sc = 0.42f + 0.58f * (depth[i] + 1f) / 2f
          pr[i] = (planetBase * sc + planetFocus * drv.k(i)) * (0.4f + 0.6f * e)
          order[i] = i
        }
        for (a in 1 until n) { val v = order[a]; var b = a - 1; while (b >= 0 && depth[order[b]] > depth[v]) { order[b + 1] = order[b]; b-- }; order[b + 1] = v }
        val vel = drv.pos.velocity; val sweep = (vel * 360f / n * 0.16f).coerceIn(-70f, 70f)
        fun planet(i: Int) {
          val r = pr[i]
          if (abs(sweep) > 1f) {   // orbit trail behind the motion
            val th = (90.0 + 360.0 * drv.ringOffset(i, n) / n).toFloat()
            drawArc(items[i].tint, th, sweep, false, Offset(cx - rx * orbitScale, oy - ry * orbitScale), Size(2 * rx * orbitScale, 2 * ry * orbitScale),
              alpha = (abs(sweep) / 70f * 0.55f).coerceIn(0f, 0.55f), style = trail)
          }
          val k = drv.k(i)
          if (k > 0.01f) drawCircle(Brush.radialGradient(listOf(lighten(items[i].tint, 0.4f).copy(alpha = 0.45f * k), Color.Transparent), Offset(px[i], py[i]), r * 1.9f), r * 1.9f, Offset(px[i], py[i]))
          drawImage(planets[i], srcOffset = IntOffset.Zero, srcSize = IntSize(PLANET_PX, PLANET_PX),
            dstOffset = IntOffset((px[i] - r).toInt(), (py[i] - r).toInt()), dstSize = IntSize((2 * r).toInt(), (2 * r).toInt()),
            alpha = (0.35f + 0.65f * (depth[i] + 1f) / 2f) * e, filterQuality = FilterQuality.Medium)
          if (k > 0.01f) drawOval(Color.White, Offset(px[i] - r * 1.45f, py[i] - r * 0.38f), Size(r * 2.9f, r * 0.76f), alpha = 0.55f * k, style = ringBack)
        }
        // back half of the orbit, planets behind the sun
        drawArc(Color.White, 180f, 180f, false, Offset(cx - rx * orbitScale, oy - ry * orbitScale), Size(2 * rx * orbitScale, 2 * ry * orbitScale), alpha = 0.16f, style = ringBack)
        for (j in 0 until n) { val i = order[j]; if (depth[i] < 0f) planet(i) }
        // the sun: corona, slowly rotating surface, anamorphic lens streak
        drawRect(corona)
        rotate(t.value * 2.2f, Offset(cx, cy)) {
          drawImage(surface, srcOffset = IntOffset.Zero, srcSize = IntSize(192, 192), dstOffset = IntOffset((cx - sunR).toInt(), (cy - sunR).toInt()),
            dstSize = IntSize((2 * sunR).toInt(), (2 * sunR).toInt()), filterQuality = FilterQuality.Medium)
        }
        drawRect(streak, Offset(0f, cy - 1.5.dp.toPx()), Size(size.width, 3.dp.toPx()))
        // lens ghosts along the axis through the screen centre
        val gx = size.width - cx; val gy = size.height - cy
        for (g in 1..3) { val f = 0.35f * g; drawCircle(Color(0xFFFFC27A), (6 + g * 7).dp.toPx(), Offset(cx + (gx - cx) * f + 140.dp.toPx() * f, cy + (gy - cy) * f), alpha = 0.025f + 0.012f * g) }
        // front half of the orbit, planets in front
        drawArc(Color.White, 0f, 180f, false, Offset(cx - rx * orbitScale, oy - ry * orbitScale), Size(2 * rx * orbitScale, 2 * ry * orbitScale), alpha = 0.28f, style = ringBack)
        for (j in 0 until n) { val i = order[j]; if (depth[i] >= 0f) planet(i) }
        // launch: the front planet rushes at the camera and fills the view with its light
        val l = fx.launch.value; val li = fx.launching
        if (l > 0f && li >= 0) {
          val ll = StyleAccel.transform(l); val r = pr[li] + (size.maxDimension) * ll
          drawCircle(lighten(items[li].tint, 0.25f), r, Offset(px[li], py[li]), alpha = (l * 1.6f).coerceAtMost(1f))
          drawRect(Color.White, alpha = ((l - 0.65f) / 0.35f).coerceIn(0f, 1f))
        }
      }
    })
    // ---------------- clock on the sun
    Column(Modifier.align(Alignment.TopCenter).padding(top = (238 - 34).dp), horizontalAlignment = Alignment.CenterHorizontally) {
      T(fmtTime(now, st.use24h), 46.sp, color = Color(0xFF4A1600), weight = FontWeight.Bold, family = SpaceGrotesk, spacing = (-1).sp)
      if (!st.use24h) T(fmtAmPm(now), 13.sp, Modifier.padding(top = 0.dp), color = Color(0xFF6A2600), weight = FontWeight.Bold, family = SpaceGrotesk, spacing = 3.sp)
    }
    // ---------------- mission data: date + weather (top-left), verse (top-right), focused app (bottom)
    Column(Modifier.padding(start = 44.dp, top = 34.dp).graphicsLayer { alpha = (fx.enter.value * 1.6f - 0.4f).coerceIn(0f, 1f) }) {
      T(java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(java.util.Date(now)), 18.sp, weight = FontWeight.Bold, family = SpaceGrotesk)
      T("DAY ${StyleData.dayOfYear()} OF ${java.time.LocalDate.now().lengthOfYear()}  ·  ORBIT ${java.time.LocalDate.now().year}", 10.sp, Modifier.padding(top = 2.dp),
        color = Color(0xFF8FA6FF), weight = FontWeight.Bold, spacing = 2.sp, family = SpaceGrotesk)
      val w = st.weather
      Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (w != null) { WeatherGlyph(WeatherApi.sky(w.code), w.isDay, 22.dp); Spacer(Modifier.width(8.dp)) }
        T(if (w == null) (if (st.place == null) "Set a ZIP in Settings" else "Forecast loading…") else "${WeatherApi.fmt(w.tempF, st.celsius)}  ${WeatherApi.describe(w.code)}", 15.sp, family = SpaceGrotesk)
      }
      StyleData.place(st)?.let { T("SURFACE CONDITIONS · ${it.uppercase()}", 9.5.sp, Modifier.padding(top = 2.dp), color = Ink.copy(alpha = 0.55f), spacing = 1.5.sp, family = SpaceGrotesk) }
    }
    st.verse?.let { v ->
      Column(Modifier.align(Alignment.TopEnd).padding(end = 44.dp, top = 34.dp).width(250.dp).graphicsLayer { alpha = (fx.enter.value * 1.6f - 0.5f).coerceIn(0f, 1f) },
        horizontalAlignment = Alignment.End) {
        T(v.text, if (v.text.length > 140) 11.5.sp else 13.sp, color = Ink.copy(alpha = 0.88f), family = SpaceGrotesk, align = androidx.compose.ui.text.style.TextAlign.End, maxLines = 7)
        T(v.reference.uppercase(), 10.sp, Modifier.padding(top = 4.dp), color = Color(0xFFFFC27A), weight = FontWeight.Bold, spacing = 2.sp, family = SpaceGrotesk)
        StyleData.notice(st)?.let { T(it, 8.5.sp, color = Ink.copy(alpha = 0.5f), family = SpaceGrotesk, align = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2) }
      }
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Crossfade(drv.focused, animationSpec = tween(260), label = "planet") { i -> T(items.getOrNull(i)?.label ?: "", 24.sp, weight = FontWeight.Bold, family = SpaceGrotesk, shadow = TextShadow) }
      T("\u25C0 \u25B6  turn the orbit   ·   OK  launch   ·   hold OK  options", 11.sp, Modifier.padding(top = 4.dp), color = Ink.copy(alpha = 0.5f), family = SpaceGrotesk, spacing = 1.sp)
    }
    // ---------------- focus anchors (the planets are drawn above; these carry focus, keys and the requesters)
    Row(Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 0f }) {
      items.forEachIndexed { i, _ -> Box(Modifier.size(2.dp).styleItem(env, items, i, drv, fx, nav)) }
    }
  }
}
