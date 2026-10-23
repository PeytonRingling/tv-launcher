package com.peyton.tvlauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ================================================================ 6) Comic book

private val InkBlack = Color(0xFF111111)
private val ComicYellow = Color(0xFFFFE14A)
private val ComicRed = Color(0xFFE8322B)
private val TopTierH = 196.dp
private val Gutter = 12.dp
private val PanelW = 142.dp
private val PanelH = 128.dp
private val Bangs = listOf("POW!", "BAM!", "ZAP!", "WHAM!", "BOOM!", "KAPOW!", "ZOOM!")

/** One dot of a Ben-Day screen (alpha mask, tinted when drawn); tiles seamlessly. */
private fun benDay(px: Int) = Baked.get("benday", px, px) { x, y ->
  val c = px / 2f; val d = kotlin.math.hypot(x + 0.5f - c, y + 0.5f - c) / (px * 0.32f)
  val a = (1f - d).coerceIn(0f, 1f).let { if (it > 0f) (it * 3f).coerceAtMost(1f) else 0f }
  Baked.argb(a, 1f, 1f, 1f)
}

/** Outlined comic lettering: black stroke under a solid fill. */
@Composable
fun Lettering(text: String, size: TextUnit, fill: Color, modifier: Modifier = Modifier, stroke: Dp = 5.dp, family: androidx.compose.ui.text.font.FontFamily = Bangers, maxLines: Int = 1) {
  Box(modifier) {
    val base = TextStyle(fontFamily = family, fontSize = size, letterSpacing = 1.sp)
    BasicText(text, style = base.copy(color = InkBlack, drawStyle = Stroke(with(androidx.compose.ui.platform.LocalDensity.current) { stroke.toPx() }, join = StrokeJoin.Round)), maxLines = maxLines)
    BasicText(text, style = base.copy(color = fill), maxLines = maxLines)
  }
}

/** Starburst polygon (jagged "POW" balloon) inside [r]; built once and scaled. */
private fun burstPath(points: Int, inner: Float, seed: Int) = Path().apply {
  for (i in 0 until points * 2) {
    val a = PI * i / points - PI / 2; val rr = if (i % 2 == 0) 1f - 0.12f * hash01(i, seed) else inner + 0.08f * hash01(i, seed + 1)
    val x = (cos(a) * rr).toFloat(); val y = (sin(a) * rr).toFloat(); if (i == 0) moveTo(x, y) else lineTo(x, y)
  }
  close()
}

@Composable
fun ComicHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.COMIC)) }
  val n = items.size
  val cols = maxOf(6, (n + 1) / 2)
  val drv = rememberFocusDriver(n)
  val fx = rememberStyleFx(env)
  val t = rememberAmbientTime(env, 24)
  val nav = remember(n, cols) { StyleNav(n, cols) }
  VerseFums(st, env.actions)
  val now = rememberNow()
  val colScroll by animateFloatAsState((drv.focused % cols - 4).coerceIn(0, (cols - 6).coerceAtLeast(0)).toFloat(), StyleSpring, label = "cs")

  Box(Modifier.fillMaxSize().background(InkBlack).testTag("style-comic")) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
      // The app grid is composed first and the top tier after it, so a focused panel's POW burst (and its scale/tilt)
      // can only ever pass *under* the clock, weather and verse panels, never over their captions.
      // ---------------- bottom tiers: the apps as panels
      Box(Modifier.fillMaxSize().padding(top = TopTierH + Gutter)) {
        // POW bursts live on their own layer *under* every panel: they flare out through the gutters and round the
        // focused panel's tilted edges, but can never cover another panel's art or a caption.
        Column(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset((-(colScroll * (PanelW + Gutter).toPx())).roundToInt(), 0) },
          verticalArrangement = Arrangement.spacedBy(Gutter)) {
          for (r in 0 until (n + cols - 1) / cols) Row(horizontalArrangement = Arrangement.spacedBy(Gutter)) {
            for (c in 0 until cols) { val i = r * cols + c; if (i < n) PowBurst(i, drv) }
          }
        }
        Column(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset((-(colScroll * (PanelW + Gutter).toPx())).roundToInt(), 0) },
          verticalArrangement = Arrangement.spacedBy(Gutter)) {
          for (r in 0 until (n + cols - 1) / cols) Row(horizontalArrangement = Arrangement.spacedBy(Gutter)) {
            for (c in 0 until cols) { val i = r * cols + c; if (i < n) AppPanel(env, items, i, drv, fx, nav, t) }
          }
        }
      }
      // ---------------- top tier: clock · weather · verse
      Row(Modifier.fillMaxWidth().height(TopTierH), horizontalArrangement = Arrangement.spacedBy(Gutter)) {
        ComicPanel(Color(0xFF4FC3F7), Modifier.weight(1.05f).fillMaxHeight(), fx, 0, speedLines = true) {
          Caption("MEANWHILE, AT HOME\u2026", Modifier.padding(10.dp))
          Column(Modifier.align(Alignment.Center).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Lettering(fmtTime(now, st.use24h) + if (st.use24h) "" else " " + fmtAmPm(now), 76.sp, Color.White, stroke = 7.dp)
            Lettering(java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(java.util.Date(now)).uppercase(), 20.sp, ComicYellow, stroke = 4.dp)
          }
        }
        ComicPanel(Color(0xFFFFB74D), Modifier.weight(0.95f).fillMaxHeight(), fx, 1) {
          val w = st.weather
          Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(96.dp).drawBehind { comicWeather(w?.code ?: 0, w?.isDay ?: true) })
          Column(Modifier.padding(14.dp)) {
            Lettering(w?.let { WeatherApi.fmt(it.tempF, st.celsius) } ?: "?!", 64.sp, Color.White, stroke = 6.dp)
            w?.hours?.take(4)?.let { hs -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { hs.forEach { Lettering("${it.label.replace(" ", "")} ${WeatherApi.fmt(it.tempF, st.celsius)}", 13.sp, Color.White, stroke = 3.dp) } } }
          }
          Caption(w?.let { "${WeatherApi.describe(it.code).uppercase()} OVER ${(StyleData.place(st) ?: "TOWN").uppercase()}!" } ?: if (st.place == null) "SET A ZIP IN SETTINGS!" else "THE FORECAST IS ON ITS WAY\u2026",
            Modifier.align(Alignment.BottomStart).padding(10.dp), maxLines = 2)
        }
        ComicPanel(Color(0xFFF06292), Modifier.weight(1.25f).fillMaxHeight(), fx, 2) {
          SpeechBubble(st, Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 34.dp))
          st.verse?.let { v -> Caption(v.reference.uppercase() + (StyleData.notice(st)?.let { "  \u00B7  $it" } ?: ""), Modifier.align(Alignment.BottomEnd).padding(8.dp), small = true, maxLines = 2) }
        }
      }
    }
    // ---------------- launch: onomatopoeia over the panel, then an ink wipe
    Box(Modifier.fillMaxSize().drawWithCache {
      val burst = burstPath(14, 0.62f, 7)
      onDrawBehind {
        val l = fx.launch.value; if (l <= 0f) return@onDrawBehind
        val pop = StyleDecel.transform((l / 0.45f).coerceIn(0f, 1f))
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.42f * pop
        rotate(-8f, c) { translate(c.x, c.y) { scale(r, r, Offset.Zero) {
          drawPath(burst, ComicYellow); drawPath(burst, InkBlack, style = Stroke(0.035f)) } } }
        drawRect(InkBlack, alpha = ((l - 0.72f) / 0.28f).coerceIn(0f, 1f))
      }
    })
    if (fx.launching >= 0) Box(Modifier.align(Alignment.Center).graphicsLayer {
      val l = fx.launch.value; val pop = StyleDecel.transform(((l - 0.08f) / 0.4f).coerceIn(0f, 1f))
      scaleX = 0.3f + 0.9f * pop; scaleY = scaleX; rotationZ = -8f; alpha = (1f - ((l - 0.8f) / 0.2f).coerceIn(0f, 1f)) * (if (l > 0.08f) 1f else 0f)
    }) { Lettering(Bangs[fx.launching % Bangs.size], 120.sp, ComicRed, stroke = 10.dp) }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.translate(x: Float, y: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) =
  drawContext.transform.let { it.translate(x, y); block(); it.translate(-x, -y) }

/** Panel: colour fill + Ben-Day dots + shading + black ink border with a white keyline; stamps in on entry. */
@Composable
private fun ComicPanel(color: Color, modifier: Modifier, fx: StyleFx, order: Int, speedLines: Boolean = false, content: @Composable BoxScope.() -> Unit) {
  Box(modifier.graphicsLayer {
    val e = StyleDecel.transform(((fx.enter.value - 0.06f * order) / 0.45f).coerceIn(0f, 1f))
    val s = 1.25f - 0.25f * e; scaleX = s; scaleY = s; rotationZ = (1f - e) * (if (order % 2 == 0) -6f else 5f); alpha = e
  }.drawWithCache {
    val dots = tiledBrush(benDay(10.dp.toPx().toInt().coerceAtLeast(6)))
    val dotTint = ColorFilter.tint(darken(color, 0.28f), BlendMode.SrcIn)
    val shade = Brush.linearGradient(listOf(Color.Transparent, Color(0x33000000)), Offset(0f, 0f), Offset(size.width, size.height))
    val rays = if (speedLines) Path().apply {
      val o = Offset(size.width * 1.1f, size.height * 1.2f)
      for (i in 0 until 26) { val a = PI + PI / 2 * i / 26.0; val a2 = a + 0.025
        moveTo(o.x, o.y); lineTo((o.x + cos(a) * size.maxDimension * 2).toFloat(), (o.y + sin(a) * size.maxDimension * 2).toFloat())
        lineTo((o.x + cos(a2) * size.maxDimension * 2).toFloat(), (o.y + sin(a2) * size.maxDimension * 2).toFloat()); close() }
    } else null
    onDrawBehind {
      drawRect(color)
      drawRect(dots, colorFilter = dotTint, alpha = 0.55f)
      rays?.let { clipRect { drawPath(it, Color.White, alpha = 0.28f) } }
      drawRect(shade)
    }
  }.drawWithContent2()) {
    content()
  }
}

/** Ink border on top of the panel's content. */
private fun Modifier.drawWithContent2() = this.drawWithCache {
  val border = Stroke(4.dp.toPx()); val key = Stroke(1.5.dp.toPx()); val i = 3.dp.toPx()
  onDrawWithContent {
    drawContent()
    drawRect(InkBlack, style = border)
    drawRect(Color.White, Offset(-i, -i), Size(size.width + 2 * i, size.height + 2 * i), style = key)
  }
}

@Composable
private fun Caption(text: String, modifier: Modifier, small: Boolean = false, maxLines: Int = 1) {
  Box(modifier.background(ComicYellow).border(2.dp, InkBlack).padding(horizontal = 8.dp, vertical = 3.dp)) {
    T(text, if (small) 10.sp else 13.sp, color = InkBlack, family = Bangers, spacing = 1.sp, maxLines = maxLines)
  }
}

@Composable
private fun SpeechBubble(st: LauncherState, modifier: Modifier) {
  val v = st.verse
  Box(modifier.drawWithCache {
    val w = size.width; val h = size.height
    val bubble = Path().apply {
      addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h * 0.88f, androidx.compose.ui.geometry.CornerRadius(w * 0.22f, h * 0.4f)))
      moveTo(w * 0.16f, h * 0.8f); lineTo(w * 0.06f, h * 1.02f); lineTo(w * 0.32f, h * 0.84f); close()
    }
    val ink = Stroke(3.5.dp.toPx(), join = StrokeJoin.Round)
    onDrawBehind { drawPath(bubble, Color.White); drawPath(bubble, InkBlack, style = ink) }
  }.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 30.dp), Alignment.Center) {
    val text = v?.text ?: if (!st.verseKey) "ADD YOUR API.BIBLE KEY IN SETTINGS!" else "HOLD ON\u2014TODAY'S VERSE IS COMING!"
    T(text.uppercase(), when { text.length > 170 -> 11.sp; text.length > 110 -> 12.5.sp; else -> 15.sp }, color = InkBlack, family = Bangers, spacing = 0.6.sp,
      align = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 7)
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.comicWeather(code: Int, day: Boolean) {
  val sky = WeatherApi.sky(code)
  val ink = Stroke(3.dp.toPx())
  val c = Offset(size.width * 0.42f, size.height * 0.4f)
  if (day) { drawCircle(ComicYellow, size.width * 0.26f, c); drawCircle(InkBlack, size.width * 0.26f, c, style = ink)
    for (i in 0 until 8) { val a = PI / 4 * i; val r1 = size.width * 0.32f; val r2 = size.width * 0.42f
      drawLine(InkBlack, Offset((c.x + cos(a) * r1).toFloat(), (c.y + sin(a) * r1).toFloat()), Offset((c.x + cos(a) * r2).toFloat(), (c.y + sin(a) * r2).toFloat()), 3.dp.toPx(), StrokeCap.Round) } }
  else { drawCircle(Color(0xFFFFF3C4), size.width * 0.24f, c); drawCircle(InkBlack, size.width * 0.24f, c, style = ink) }
  if (sky != Sky.SUN) {
    val cl = Path().apply {
      val b = size.height * 0.78f
      addOval(androidx.compose.ui.geometry.Rect(size.width * 0.28f, b - size.height * 0.30f, size.width * 0.62f, b))
      addOval(androidx.compose.ui.geometry.Rect(size.width * 0.5f, b - size.height * 0.38f, size.width * 0.92f, b))
      addOval(androidx.compose.ui.geometry.Rect(size.width * 0.12f, b - size.height * 0.2f, size.width * 0.44f, b))
    }
    drawPath(cl, Color.White); drawPath(cl, InkBlack, style = ink)
  }
}

/** The focused panel's starburst, on the layer beneath the panels; same tilt as the panel it belongs to. */
@Composable
private fun PowBurst(i: Int, drv: FocusDriver) {
  Box(Modifier.size(PanelW, PanelH).graphicsLayer { rotationZ = (if (i % 2 == 0) -2.5f else 2.5f) * drv.k(i) }.drawWithCache {
    val burst = burstPath(12, 0.7f, i + 3)
    onDrawBehind {
      val k = drv.k(i)
      if (k > 0.01f) {
        val r = size.maxDimension * (0.72f + 0.3f * k)
        translate(size.width / 2, size.height / 2) { scale(r * k, r * k, Offset.Zero) { drawPath(burst, ComicYellow); drawPath(burst, ComicRed, style = Stroke(0.03f)) } }
      }
    }
  })
}

@Composable
private fun AppPanel(env: HomeEnv, items: List<StyleItem>, i: Int, drv: FocusDriver, fx: StyleFx, nav: StyleNav, t: State<Float>) {
  val item = items[i]
  Box(Modifier.zIndex(if (drv.focused == i) 2f else 0f).size(PanelW, PanelH)
    .graphicsLayer {
      val e = StyleDecel.transform(stagger(fx.enter.value, 0.2f, 0.035f, i, 0.4f))
      val k = drv.k(i)
      val s = (1.3f - 0.3f * e) * (1f + 0.12f * k); scaleX = s; scaleY = s
      rotationZ = (1f - e) * (if (i % 2 == 0) 7f else -6f) + (if (i % 2 == 0) -2.5f else 2.5f) * k
      alpha = e
    }
    .drawWithCache {
      val dots = tiledBrush(benDay(8.dp.toPx().toInt().coerceAtLeast(5)))
      val dotTint = ColorFilter.tint(darken(item.tint, 0.35f), BlendMode.SrcIn)
      val bg = lighten(item.tint, 0.35f)
      onDrawBehind {
        drawRect(bg); drawRect(dots, colorFilter = dotTint, alpha = 0.6f)
      }
    }
    .styleItem(env, items, i, drv, fx, nav)
    .drawWithCache {
      // ink boil: three hand-jittered outlines, cycled ~8×/s while focused
      val boilPaths = List(3) { v -> Path().apply {
        val j = 2.4.dp.toPx(); fun jx(p: Int) = (hash01(p, 90 + v) - 0.5f) * j
        moveTo(jx(1), jx(2)); lineTo(size.width + jx(3), jx(4)); lineTo(size.width + jx(5), size.height + jx(6)); lineTo(jx(7), size.height + jx(8)); close() } }
      val ink = Stroke(4.dp.toPx(), join = StrokeJoin.Round)
      onDrawWithContent {
        drawContent()
        val k = drv.k(i)
        val v = if (k > 0.5f) ((t.value * 8f).toInt() % 3) else 0
        drawPath(boilPaths[v], InkBlack, style = ink)
      }
    }, Alignment.TopCenter) {
    ItemArt(item, Modifier.padding(top = 14.dp).size(62.dp).clip(RoundedCornerShape(14.dp)).border(3.dp, InkBlack, RoundedCornerShape(14.dp)))
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(Color.White).border(2.dp, InkBlack).padding(horizontal = 7.dp, vertical = 2.dp)) {
      T(item.label.uppercase(), 13.sp, color = InkBlack, family = Bangers, spacing = 1.sp)
    }
  }
}
