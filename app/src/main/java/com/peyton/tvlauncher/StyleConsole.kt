package com.peyton.tvlauncher

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ================================================================ 2) Console dashboard (an original take on a game-console home)

private val TileW = 188.dp
private val TileH = 106.dp
private val TileGap = 16.dp
private val RowTop = 300.dp
private val RowStart = 56.dp
private val TileShape = RoundedCornerShape(10.dp)
private val ConsoleAccent = Color(0xFF3DDC97)

/** Tiny pre-blurred copies of app art for the hero backdrop (two bilinear down-steps ≈ a wide box blur). */
private object HeroBlur {
  private val cache = HashMap<String, ImageBitmap>()
  fun of(item: StyleItem): ImageBitmap? {
    val src = item.app?.banner ?: item.app?.tile ?: return null
    return synchronized(cache) {
      cache.getOrPut(item.key) {
        val b = src.asAndroidBitmap()
        val mid = android.graphics.Bitmap.createScaledBitmap(b, 96, (96f * b.height / b.width).toInt().coerceAtLeast(8), true)
        android.graphics.Bitmap.createScaledBitmap(mid, 24, (24f * b.height / b.width).toInt().coerceAtLeast(4), true).asImageBitmap()
      }
    }
  }
}

@Composable
fun ConsoleHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.CONSOLE)) }
  val drv = rememberFocusDriver(items.size)
  val fx = rememberStyleFx(env)
  val nav = remember(items.size) { StyleNav(items.size, items.size) }
  VerseFums(st, env.actions)
  val now = rememberNow()
  val visible = 4.5f
  val scroll = { (drv.pos.value - 1f).coerceIn(0f, (items.size - visible).coerceAtLeast(0f)) }

  Box(Modifier.fillMaxSize().background(Color(0xFF07090C)).testTag("style-console")) {
    // ---------------- hero backdrop: focused app's art, blurred, crossfading as focus moves
    Crossfade(drv.focused, animationSpec = tween(650), label = "hero") { i ->
      val item = items.getOrNull(i) ?: return@Crossfade
      Box(Modifier.fillMaxSize()) {
        val blur = remember(item.key) { HeroBlur.of(item) }
        if (blur != null) Image(blur, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        else Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(item.tint, Color(0xFF07090C)), Offset(1400f, 200f), 1600f)))
        // sharp key art on the right, melting into the backdrop
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).fillMaxHeight(0.66f)) {
          ItemArt(item, Modifier.fillMaxSize(), preferBanner = true)
          Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF07090C), Color(0x9907090C), Color.Transparent), endX = 700f)))
          Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xFF07090C)))))
        }
      }
    }
    // readability scrims
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE607090C), Color(0x6607090C), Color.Transparent))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF207090C))))

    val enter = fx.enter
    // ---------------- top bar
    Row(Modifier.fillMaxWidth().padding(start = RowStart, end = 48.dp, top = 28.dp).graphicsLayer {
      val e = StyleDecel.transform((enter.value / 0.5f).coerceIn(0f, 1f)); alpha = e; translationY = (1f - e) * -16.dp.toPx()
    }, verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(ConsoleAccent, Color(0xFF1B8A5E)))), Alignment.Center) {
        T("P", 16.sp, weight = FontWeight.Bold, color = Color(0xFF06140E), family = SpaceGrotesk)
      }
      Spacer(Modifier.width(12.dp))
      T("Home", 17.sp, weight = FontWeight.Bold, family = SpaceGrotesk)
      Spacer(Modifier.width(22.dp))
      T("Apps ${st.favoriteApps.size}", 14.sp, color = Ink.copy(alpha = 0.6f), family = SpaceGrotesk)
      Spacer(Modifier.weight(1f))
      st.weather?.let { w ->
        WeatherGlyph(WeatherApi.sky(w.code), w.isDay, 20.dp)
        Spacer(Modifier.width(6.dp))
        T(WeatherApi.fmt(w.tempF, st.celsius), 16.sp, weight = FontWeight.Bold, family = SpaceGrotesk)
        Spacer(Modifier.width(18.dp))
      }
      T(fmtTime(now, st.use24h) + if (st.use24h) "" else " " + fmtAmPm(now), 16.sp, weight = FontWeight.Bold, family = SpaceGrotesk)
    }

    // ---------------- hero text for the focused item (slides with each change)
    Crossfade(drv.focused, Modifier.padding(start = RowStart, top = 104.dp), animationSpec = tween(320), label = "title") { i ->
      val item = items.getOrNull(i) ?: return@Crossfade
      Column(Modifier.width(440.dp)) {
        val recent = item.app != null && st.recents.firstOrNull() == item.app.pkg
        T(when { recent -> "JUMP BACK IN"; item.kind == StyleItem.Kind.APP -> "PINNED"; else -> "SYSTEM" }, 12.sp, weight = FontWeight.Bold, color = ConsoleAccent, spacing = 2.sp, family = SpaceGrotesk)
        T(item.label, 50.sp, Modifier.padding(top = 4.dp), weight = FontWeight.Bold, spacing = (-1).sp, family = SpaceGrotesk, shadow = TextShadow)
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp)) {
            T(if (item.kind == StyleItem.Kind.APP) "\u25B6  Open" else "\u25B6  Go", 14.sp, weight = FontWeight.Bold, color = Color(0xFF07090C), family = SpaceGrotesk)
          }
          Spacer(Modifier.width(10.dp))
          if (item.app != null) Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            T("\u2261  Hold OK for options", 14.sp, weight = FontWeight.Bold, family = SpaceGrotesk)
          }
        }
      }
    }

    // ---------------- tile row
    Box(Modifier.padding(top = RowTop).fillMaxWidth().height(TileH + 40.dp)) {
      Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(start = RowStart, top = 14.dp)
        .graphicsLayer { translationX = -scroll() * (TileW + TileGap).toPx() }, horizontalArrangement = Arrangement.spacedBy(TileGap)) {
        items.forEachIndexed { i, item -> ConsoleTile(env, items, i, item, drv, fx, nav) }
      }
    }

    // ---------------- "for today" strip: verse + weather
    Row(Modifier.align(Alignment.BottomStart).padding(start = RowStart, end = 48.dp, bottom = 26.dp).fillMaxWidth().graphicsLayer {
      val e = StyleDecel.transform(((enter.value - 0.25f) / 0.6f).coerceIn(0f, 1f)); alpha = e; translationY = (1f - e) * 30.dp.toPx()
    }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      val v = st.verse
      Column(Modifier.weight(1.7f).height(78.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x26FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        T("VERSE OF THE DAY" + (v?.let { "  ·  ${it.reference}" } ?: ""), 11.sp, weight = FontWeight.Bold, color = ConsoleAccent, spacing = 1.5.sp, family = SpaceGrotesk)
        T(v?.text ?: (if (!st.verseKey) NO_VERSE_KEY else "Loading today's verse…"), 13.5.sp, Modifier.padding(top = 4.dp), color = Ink.copy(alpha = 0.92f), maxLines = 2, family = SpaceGrotesk)
        StyleData.notice(st)?.let { T(it, 9.5.sp, color = Ink.copy(alpha = 0.55f), family = SpaceGrotesk) }
      }
      Column(Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x26FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        T("WEATHER" + (StyleData.place(st)?.let { "  ·  ${it.uppercase()}" } ?: ""), 11.sp, weight = FontWeight.Bold, color = ConsoleAccent, spacing = 1.5.sp, family = SpaceGrotesk, maxLines = 1)
        val w = st.weather
        if (w == null) T(if (st.place == null) "Set a ZIP in Settings" else "Waiting for the forecast…", 13.5.sp, Modifier.padding(top = 4.dp), family = SpaceGrotesk)
        else Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          w.hours.take(5).forEach { h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              T(h.label, 10.sp, color = Ink.copy(alpha = 0.7f), family = SpaceGrotesk)
              WeatherGlyph(WeatherApi.sky(h.code), h.isDay, 15.dp)
              T(WeatherApi.fmt(h.tempF, st.celsius), 12.sp, weight = FontWeight.Bold, family = SpaceGrotesk)
            }
          }
        }
      }
    }

    // ---------------- launch: the focused tile grows into the whole screen in its own colour
    Box(Modifier.fillMaxSize().drawBehind {
      val l = fx.launch.value; val i = fx.launching
      if (l <= 0f || i < 0) return@drawBehind
      val e = StyleDecel.transform((l / 0.8f).coerceIn(0f, 1f))
      val stride = (TileW + TileGap).toPx()
      val x0 = RowStart.toPx() + (i - scroll()) * stride; val y0 = (RowTop + 14.dp).toPx()
      val left = x0 * (1 - e); val top = y0 * (1 - e)
      val w = TileW.toPx() + (size.width - TileW.toPx()) * e; val h = TileH.toPx() + (size.height - TileH.toPx()) * e
      drawRoundRect(items[i].tint, Offset(left, top), Size(w, h), CornerRadius(10.dp.toPx() * (1 - e)))
      drawRect(Color.Black, alpha = ((l - 0.7f) / 0.3f).coerceIn(0f, 1f))
    })
  }
}

@Composable
private fun ConsoleTile(env: HomeEnv, items: List<StyleItem>, i: Int, item: StyleItem, drv: FocusDriver, fx: StyleFx, nav: StyleNav) {
  Box(Modifier.size(TileW, TileH)
    .graphicsLayer {
      // entry: tiles rise in a quick stagger
      val e = StyleDecel.transform(((fx.enter.value - 0.15f - 0.05f * i.coerceAtMost(8)) / 0.5f).coerceIn(0f, 1f))
      val k = drv.k(i)
      val s = 1f + 0.10f * k; scaleX = s; scaleY = s
      translationY = (1f - e) * 60.dp.toPx()
      alpha = e
    }
    .drawWithCache {
      val m = 18.dp.toPx().toInt()
      val shadow = SoftGlow.custom(size.width.toInt(), size.height.toInt(), m, 10.dp.toPx(), 0.55f, black = true)
      val outline = 3.dp.toPx(); val gap = 3.dp.toPx(); val r = 10.dp.toPx()
      onDrawBehind {
        val k = drv.k(i)
        drawImage(shadow, Offset(-m.toFloat(), -m + 6.dp.toPx() * k), alpha = 0.35f + 0.5f * k)
        if (k > 0.01f) drawRoundRect(Color.White, Offset(-gap - outline / 2, -gap - outline / 2), Size(size.width + 2 * gap + outline, size.height + 2 * gap + outline),
          CornerRadius(r + gap + outline / 2), alpha = k, style = Stroke(outline))
      }
    }
    .styleItem(env, items, i, drv, fx, nav)
    .clip(TileShape)
    .drawWithCache {
      // sheen: one diagonal light band sweeps across as focus arrives (and back as it leaves)
      val band = Brush.linearGradient(listOf(Color.Transparent, Color(0x66FFFFFF), Color.Transparent), Offset(0f, 0f), Offset(size.width * 0.35f, size.height))
      val top = Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color.Transparent), endY = size.height * 0.5f)
      onDrawWithContent {
        drawContent(); drawRect(top)
        val k = drv.k(i)
        if (k in 0.02f..0.98f) translate(left = (-0.6f + 1.9f * k) * size.width) { drawRect(band, Offset(-size.width, 0f), Size(size.width * 2f, size.height), alpha = kotlin.math.sin(k * Math.PI).toFloat()) }
      }
    }
  ) {
    ItemArt(item, Modifier.fillMaxSize(), preferBanner = true)
    if (item.app?.banner == null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))).padding(start = 10.dp, bottom = 7.dp, top = 14.dp)) {
      T(item.label, 13.sp, weight = FontWeight.Bold, family = SpaceGrotesk, shadow = TextShadow)
    }
  }
}
