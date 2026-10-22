package com.peyton.tvlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ================================================================ 1) Retro terminal (green phosphor CRT)

private val Phos = Color(0xFF8CFFB0)
private val PhosMid = Color(0xFF4FD27E)
private val PhosDim = Color(0xFF2B7F4C)
private val CrtBg = Color(0xFF031008)
private val PhosGlow = Shadow(Color(0xD93CFF82), Offset.Zero, 16f)
private val PhosGlowSoft = Shadow(Color(0x993CFF82), Offset.Zero, 10f)
private val RowH = 30.dp

@Composable
fun RetroHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.RETRO)) }
  val drv = rememberFocusDriver(items.size)
  val fx = rememberStyleFx(env)
  val t = rememberAmbientTime(env, 30)
  val nav = remember(items.size) { StyleNav(items.size, 1) }
  VerseFums(st, env.actions)
  val now = rememberNow()

  Box(Modifier.fillMaxSize().background(Color.Black).testTag("style-retro")) {
    // ---- the tube: power-on opens a bright line vertically; launching collapses it to a line, then a dot.
    Box(Modifier.fillMaxSize().graphicsLayer {
      val on = (fx.enter.value / 0.32f).coerceIn(0f, 1f)
      val l = fx.launch.value
      val offV = (l / 0.55f).coerceIn(0f, 1f); val offH = ((l - 0.55f) / 0.45f).coerceIn(0f, 1f)
      scaleY = (0.006f + 0.994f * StyleDecel.transform(on)) * (1f - 0.994f * StyleAccel.transform(offV))
      scaleX = (0.82f + 0.18f * StyleDecel.transform(on)) * (1f - 0.985f * offH)
    }.background(CrtBg)) {
      RetroScreen(env, items, drv, fx, nav, now, t)
      // white-hot flash while the beam opens / closes
      Box(Modifier.fillMaxSize().drawBehind {
        val on = (fx.enter.value / 0.32f).coerceIn(0f, 1f); val l = fx.launch.value
        val a = maxOf(1f - on, (l / 0.55f).coerceIn(0f, 1f) * 0.9f)
        if (a > 0.003f) drawRect(Color(0xFFE9FFF0), alpha = a)
      })
    }
    // ---- glass: scanlines, vignette, curved corners and reflection — one static layer, recorded once.
    Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
      val step = 3.dp.toPx(); val lineH = step * 0.42f
      val vignette = Brush.radialGradient(listOf(Color.Transparent, Color(0x00000000), Color(0xB0000000)), Offset(size.width / 2, size.height / 2), size.maxDimension * 0.62f)
      val glare = Brush.linearGradient(listOf(Color(0x14FFFFFF), Color.Transparent), Offset(0f, 0f), Offset(size.width * 0.45f, size.height * 0.55f))
      val r = 64.dp.toPx()
      val frame = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(10.dp.toPx(), 8.dp.toPx(), size.width - 10.dp.toPx(), size.height - 8.dp.toPx(), CornerRadius(r, r * 0.9f)))
      }
      onDrawBehind {
        var y = 0f
        while (y < size.height) { drawRect(Color.Black, Offset(0f, y + step - lineH), Size(size.width, lineH), alpha = 0.28f); y += step }
        drawRect(vignette); drawRect(glare)
        drawPath(frame, Color.Black)
      }
    })
    // ---- rolling scan band + mains flicker: transform/alpha of tiny layers only.
    Box(Modifier.fillMaxWidth().height(140.dp).graphicsLayer {
      val p = (t.value / 7.5f) % 1f
      translationY = -140.dp.toPx() + p * (size.height * 0 + 540.dp.toPx() + 280.dp.toPx())
    }.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x0E9CFFC0), Color(0x169CFFC0), Color(0x0E9CFFC0), Color.Transparent))))
    Box(Modifier.fillMaxSize().drawBehind {
      val f = t.value
      val a = 0.018f + 0.014f * kotlin.math.sin(f * 61f) * kotlin.math.sin(f * 7.3f)
      drawRect(Color.Black, alpha = a.coerceIn(0f, 0.05f))
    })
  }
}

@Composable
private fun RetroScreen(env: HomeEnv, items: List<StyleItem>, drv: FocusDriver, fx: StyleFx, nav: StyleNav, now: Long, t: State<Float>) {
  val st = env.st
  val zdt = remember(now / 1000) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()) }
  val clock = remember(zdt.minute, zdt.hour, st.use24h) { if (st.use24h) zdt.format(DateTimeFormatter.ofPattern("HH:mm")) else zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) }
  // Typing-in: the clock types itself after the beam opens (≈ 0.35 … 0.8 of the entry).
  val typed by remember(clock) { derivedStateOf { (((fx.enter.value - 0.34f) / 0.42f).coerceIn(0f, 1f) * clock.length).toInt() } }
  val date = remember(zdt.dayOfYear) { zdt.format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.US)).uppercase() + "   DAY ${zdt.dayOfYear}" }
  Box(Modifier.fillMaxSize().padding(start = 64.dp, end = 60.dp, top = 44.dp, bottom = 34.dp)) {
    // ---------------- left: the session
    Column(Modifier.width(470.dp)) {
      T("GLASSDOCK/OS 4.4   TTY1   ${st.favoriteApps.size} PROGRAMS", 19.sp, color = PhosDim, family = Vt323, shadow = PhosGlowSoft)
      T("READY.", 19.sp, color = PhosMid, family = Vt323, shadow = PhosGlowSoft)
      Spacer(Modifier.height(10.dp))
      T("> time", 22.sp, color = PhosMid, family = Vt323, shadow = PhosGlowSoft)
      Row(verticalAlignment = Alignment.Bottom) {
        T(clock.take(typed).ifEmpty { " " }, 104.sp, Modifier.padding(start = 4.dp), color = Phos, family = Vt323, shadow = PhosGlow)
        // block cursor: rides after the typed text while typing, then blinks
        Box(Modifier.padding(start = 6.dp, bottom = 22.dp).size(30.dp, 58.dp).drawBehind {
          val typing = typed < clock.length
          val on = typing || ((t.value * 1.9f) % 1f) < 0.55f
          if (on) drawRect(Phos, alpha = 0.92f)
        })
      }
      T(date, 22.sp, color = PhosMid, family = Vt323, shadow = PhosGlowSoft)
      Spacer(Modifier.height(14.dp))
      T("> wx", 22.sp, color = PhosMid, family = Vt323, shadow = PhosGlowSoft)
      val wx = StyleData.weatherLine(st)
      T(if (wx == null) (if (st.place == null) "NO LOCATION. SET A ZIP IN SETTINGS." else "FETCHING FORECAST…") else "${wx.uppercase()}  ·  ${StyleData.place(st)?.uppercase() ?: ""}",
        24.sp, color = Phos, family = Vt323, shadow = PhosGlowSoft, maxLines = 2)
      st.weather?.hours?.take(5)?.takeIf { it.isNotEmpty() }?.let { hs ->
        T(hs.joinToString("   ") { "${it.label.replace(" ", "")} ${WeatherApi.fmt(it.tempF, st.celsius)}" }, 20.sp, color = PhosDim, family = Vt323, shadow = PhosGlowSoft)
      }
      Spacer(Modifier.height(16.dp))
      val v = st.verse
      if (v != null) {
        T("> cat verse.txt", 22.sp, color = PhosMid, family = Vt323, shadow = PhosGlowSoft)
        T("\u201C${v.text}\u201D", if (v.text.length > 150) 21.sp else 24.sp, Modifier.padding(top = 2.dp), color = Phos, family = Vt323, shadow = PhosGlowSoft, maxLines = 6)
        T("  -- ${v.reference.uppercase()}" + (StyleData.notice(st)?.let { "   $it" } ?: ""), 18.sp, Modifier.padding(top = 4.dp), color = PhosMid, family = Vt323, shadow = PhosGlowSoft, maxLines = 2)
      }
    }
    // ---------------- right: PROGRAMS menu
    Column(Modifier.align(Alignment.TopEnd).width(330.dp)) {
      val visible = 11
      // double-ruled box drawn like box-drawing characters, title cut into the top rule
      Box(Modifier.fillMaxWidth().drawBehind {
        val o = 2.dp.toPx(); val g = 4.dp.toPx(); val c = PhosMid
        for (d in listOf(0f, g)) drawRect(c, Offset(d, 11.dp.toPx() + d), Size(size.width - 2 * d, size.height - 11.dp.toPx() - 2 * d), alpha = 0.85f,
          style = androidx.compose.ui.graphics.drawscope.Stroke(o * 0.6f))
      }.padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 10.dp)) {
      Box(Modifier.fillMaxWidth().height(RowH * visible)
        .drawWithContent { clipRect { this@drawWithContent.drawContent() } }) {
        val scrollRows = { (drv.pos.value - (visible - 3)).coerceIn(0f, (items.size - visible).coerceAtLeast(0).toFloat()) }
        // phosphor ghost: the bar's afterglow trails its motion (strength = spring velocity)
        Box(Modifier.fillMaxWidth().height(RowH).graphicsLayer {
          val v = drv.pos.velocity
          translationY = (drv.pos.value - v * 0.07f - scrollRows()) * RowH.toPx()
          alpha = (kotlin.math.abs(v) * 0.05f).coerceIn(0f, 0.35f)
        }.background(PhosMid))
        // green rows
        Column(Modifier.graphicsLayer { translationY = -scrollRows() * RowH.toPx() }) {
          items.forEachIndexed { i, it -> RetroRow(i, it, Phos, PhosDim, Modifier.styleItem(env, items, i, drv, fx, nav)) }
        }
        // the inverse bar: a green bar with the same rows in dark ink, clipped to the bar (no per-row recomposition)
        Box(Modifier.matchParentSize().drawWithContent {
          val y = (drv.pos.value - scrollRows()) * RowH.toPx()
          drawRect(Phos, Offset(0f, y), Size(size.width, RowH.toPx()))
          clipRect(top = y, bottom = y + RowH.toPx()) { this@drawWithContent.drawContent() }
        }) {
          Column(Modifier.graphicsLayer { translationY = -scrollRows() * RowH.toPx() }) {
            items.forEachIndexed { i, it -> RetroRow(i, it, CrtBg, CrtBg.copy(alpha = 0.7f), Modifier, glow = false) }
          }
        }
        // blinking block cursor at the end of the bar
        Box(Modifier.align(Alignment.TopEnd).padding(end = 10.dp).size(12.dp, 20.dp).graphicsLayer {
          translationY = (drv.pos.value - scrollRows()) * RowH.toPx() + 5.dp.toPx()
          alpha = if (((t.value * 1.9f) % 1f) < 0.55f) 1f else 0f
        }.background(CrtBg))
      }
      Box(Modifier.offset(x = 12.dp, y = (-22).dp).background(CrtBg).padding(horizontal = 6.dp)) {
        T("PROGRAMS", 20.sp, color = Phos, family = Vt323, shadow = PhosGlowSoft)
      }
      }
      T("[\u2191\u2193] SELECT  [OK] RUN  [HOLD] OPTIONS", 17.sp, Modifier.padding(top = 6.dp), color = PhosDim, family = Vt323, shadow = PhosGlowSoft)
      val pct = remember(now / 60_000) { "MEM 640K OK   UP ${(android.os.SystemClock.uptimeMillis() / 3_600_000)}H" }
      T(pct, 17.sp, color = PhosDim, family = Vt323, shadow = PhosGlowSoft)
    }
    // launch line: RUN <APP>…
    Box(Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = if (fx.launching >= 0) 1f else 0f }) {
      T("> run ${items.getOrNull(fx.launching.coerceAtLeast(0))?.label?.lowercase()?.replace(' ', '_') ?: ""}  ...", 22.sp, color = Phos, family = Vt323, shadow = PhosGlow)
    }
  }
}

@Composable
private fun RetroRow(i: Int, item: StyleItem, ink: Color, dim: Color, modifier: Modifier, glow: Boolean = true) {
  Row(modifier.fillMaxWidth().height(RowH).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    T("%02d".format(i + 1), 22.sp, color = dim, family = Vt323, shadow = if (glow) PhosGlowSoft else null)
    Spacer(Modifier.width(14.dp))
    T(item.label.uppercase(), 25.sp, color = ink, family = Vt323, shadow = if (glow) PhosGlowSoft else null)
  }
}
