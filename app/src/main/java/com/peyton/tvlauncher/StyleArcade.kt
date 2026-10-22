package com.peyton.tvlauncher

import android.view.KeyEvent as NKey
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ================================================================ 7) Arcade cabinet

private val CartW = 112.dp
private val CartH = 140.dp
private val CartGap = 22.dp
private val Bulb = Color(0xFFFFE9A8)
private val ArcPink = Color(0xFFFF2E63)
private val ArcCyan = Color(0xFF28E7FF)
private const val ATTRACT_AFTER_MS = 25_000L

/** Space-invader-ish (original) 11×8 sprite for attract mode. */
private val Sprite = listOf("..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX", "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX...")

@Composable
fun ArcadeHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.ARCADE)) }
  val n = items.size
  val cols = 6
  val drv = rememberFocusDriver(n)
  val fx = rememberStyleFx(env)
  val t = rememberAmbientTime(env, 30)
  val nav = remember(n) { StyleNav(n, cols) }
  VerseFums(st, env.actions)
  val now = rememberNow()
  // attract mode after a quiet spell; the first key only wakes it
  var lastKey by remember { mutableLongStateOf(0L) }
  var attract by remember { mutableStateOf(false) }
  LaunchedEffect(lastKey, env.interactive) { attract = false; if (env.interactive) { delay(ATTRACT_AFTER_MS); attract = true } }
  val attractK by animateFloatAsState(if (attract) 1f else 0f, androidx.compose.animation.core.tween(600), label = "attract")
  val rows = (n + cols - 1) / cols
  val rowScroll by animateFloatAsState((drv.focused / cols - 1).coerceIn(0, (rows - 2).coerceAtLeast(0)).toFloat(), StyleSpring, label = "rs")

  Box(Modifier.fillMaxSize().background(Color(0xFF0D0716)).testTag("style-arcade").onPreviewKeyEvent { e ->
    lastKey = e.nativeKeyEvent.eventTime + 1
    if (attract && e.nativeKeyEvent.action == NKey.ACTION_DOWN) { attract = false; true } else false
  }) {
    // ---------------- cabinet side art + bezel (static layer)
    Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
      val bands = listOf(ArcPink, Color(0xFFFF9F1C), ArcCyan)
      val bezel = Brush.verticalGradient(listOf(Color(0xFF1A0F2B), Color(0xFF0D0716)))
      onDrawBehind {
        drawRect(bezel)
        for ((bi, c) in bands.withIndex()) {
          val w = 10.dp.toPx(); val o = 14.dp.toPx() + bi * 16.dp.toPx()
          val p = Path().apply { moveTo(o, size.height); lineTo(o + w, size.height); lineTo(o + w + size.height * 0.35f, 0f); lineTo(o + size.height * 0.35f, 0f); close() }
          drawPath(p, c, alpha = 0.22f)
          val q = Path().apply { moveTo(size.width - o, 0f); lineTo(size.width - o - w, 0f); lineTo(size.width - o - w - size.height * 0.35f, size.height); lineTo(size.width - o - size.height * 0.35f, size.height); close() }
          drawPath(q, c, alpha = 0.22f)
        }
      }
    })
    // ---------------- marquee with chasing bulbs
    Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(860.dp, 100.dp).drawWithCache {
      val glow = Baked.get("bulb-glow", 48, 48) { x, y ->
        val d = kotlin.math.hypot(x - 23.5f, y - 23.5f) / 24f; val a = (1f - d).coerceIn(0f, 1f); Baked.argb(a * a * 0.9f, 1f, 0.92f, 0.66f)
      }
      val back = Brush.verticalGradient(listOf(Color(0xFFFF4D6D), Color(0xFFB5179E), Color(0xFF560BAD)))
      val sheen = Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color.Transparent), endY = size.height * 0.45f)
      val pitch = 22.dp.toPx(); val inset = 9.dp.toPx(); val r = 3.2.dp.toPx(); val g = 13.dp.toPx()
      val pts = ArrayList<Offset>()
      run { var x = inset; while (x < size.width - inset) { pts.add(Offset(x, inset)); x += pitch }
        var y = inset; while (y < size.height - inset) { pts.add(Offset(size.width - inset, y)); y += pitch }
        x = size.width - inset; while (x > inset) { pts.add(Offset(x, size.height - inset)); x -= pitch }
        y = size.height - inset; while (y > inset) { pts.add(Offset(inset, y)); y -= pitch } }
      onDrawBehind {
        drawRoundRect(back, cornerRadius = CornerRadius(14.dp.toPx())); drawRoundRect(sheen, cornerRadius = CornerRadius(14.dp.toPx()))
        drawRoundRect(Color(0xFF1A0F2B), cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(4.dp.toPx()))
        val chase = (t.value * (if (attract) 16f else 7f)).toInt()
        for ((i, p) in pts.withIndex()) {
          val lit = (i + chase) % 3 == 0
          if (lit) drawImage(glow, dstOffset = androidx.compose.ui.unit.IntOffset((p.x - g).toInt(), (p.y - g).toInt()), dstSize = androidx.compose.ui.unit.IntSize((2 * g).toInt(), (2 * g).toInt()))
          drawCircle(if (lit) Bulb else Color(0xFF6B4A3A), r, p)
        }
      }
    }, Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PixelText("\u2605 GLASS DOCK ARCADE \u2605", 13.sp, Bulb)
        Spacer(Modifier.height(8.dp))
        PixelText(fmtTime(now, st.use24h) + if (st.use24h) "" else " " + fmtAmPm(now), 34.sp, Color.White, depth = 4.dp)
      }
    }
    // ---------------- the rack: two shelves of cartridge slots
    Box(Modifier.align(Alignment.TopCenter).padding(top = 116.dp).size((CartW + CartGap) * cols - CartGap + 40.dp, CartH * 2 + 66.dp).clipToBounds()) {
      Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 38.dp).graphicsLayer { translationY = -rowScroll * (CartH + 20.dp).toPx() }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for (r in 0 until rows) Box {
          // slot strip the carts sit in
          Box(Modifier.align(Alignment.BottomStart).offset(x = (-12).dp, y = 6.dp).size((CartW + CartGap) * cols - CartGap + 24.dp, 40.dp).clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A1E3E), Color(0xFF120A1E)))))
          Row(horizontalArrangement = Arrangement.spacedBy(CartGap)) {
            for (c in 0 until cols) { val i = r * cols + c; if (i < n) Cartridge(env, items, i, drv, fx, nav, t, attractK) else Spacer(Modifier.width(CartW)) }
          }
          Box(Modifier.align(Alignment.BottomStart).offset(x = (-12).dp, y = 6.dp).size((CartW + CartGap) * cols - CartGap + 24.dp, 18.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF3B2B57), Color(0xFF1C1230)))))
        }
      }
    }
    // ---------------- control panel strip: insert coin · weather · hi-score; verse ticker
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 14.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.graphicsLayer { alpha = if (((t.value * 1.4f) % 1f) < 0.6f) 1f else 0.15f }) { PixelText("INSERT COIN", 10.sp, ArcCyan) }
        Spacer(Modifier.weight(1f))
        PixelText(StyleData.weatherLine(st)?.uppercase()?.let { "WX $it" } ?: if (st.place == null) "WX SET ZIP" else "WX ...", 10.sp, Color.White)
        Spacer(Modifier.weight(1f))
        PixelText("HI ${java.text.SimpleDateFormat("MM-dd-yy", java.util.Locale.US).format(java.util.Date(now))}", 10.sp, ArcPink)
      }
      val v = st.verse
      if (v != null) {
        val tick = "\u2605 ${v.text.uppercase()}  \u2014 ${v.reference.uppercase()}" + (StyleData.notice(st)?.let { "  \u00B7  $it" } ?: "") + "   "
        var w by remember { mutableIntStateOf(0) }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF05030A)).clipToBounds()) {
          Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer {
            if (w > 0) translationX = -((t.value * 70.dp.toPx()) % w)
          }.align(Alignment.CenterStart)) {
            Box(Modifier.onSizeChanged { w = it.width }) { PixelText(tick, 9.sp, Color(0xFFFFC94D)) }
            PixelText(tick, 9.sp, Color(0xFFFFC94D))
          }
        }
      }
    }
    // ---------------- attract mode
    if (attractK > 0.001f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = attractK }.background(Color(0xAA05030A)).drawBehind {
      val px = 6.dp.toPx(); val sw = Sprite[0].length * px
      val x = ((t.value * 120.dp.toPx()) % (size.width + sw)) - sw; val y = size.height * 0.62f + kotlin.math.sin(t.value * 4f) * 8.dp.toPx()
      for ((ry, row) in Sprite.withIndex()) for ((rx, ch) in row.withIndex()) if (ch == 'X')
        drawRect(if (((t.value * 2).toInt() % 2) == 0) ArcCyan else Color(0xFF5CFF8A), Offset(x + rx * px, y + ry * px), Size(px, px))
    }, Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.graphicsLayer { alpha = if (((t.value * 1.6f) % 1f) < 0.55f) 1f else 0f }) { PixelText("INSERT COIN", 40.sp, Bulb, depth = 5.dp) }
        Spacer(Modifier.height(18.dp))
        PixelText("PRESS ANY BUTTON", 13.sp, Color.White)
      }
    }
    // ---------------- launch: LOADING bar; return: PLAYER 1 READY
    if (fx.launching >= 0) Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((fx.launch.value - 0.25f) / 0.2f).coerceIn(0f, 1f) }.background(Color(0xFF05030A)), Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PixelText("LOADING ${items[fx.launching].label.uppercase()}", 16.sp, Color.White)
        Box(Modifier.padding(top = 16.dp).size(360.dp, 22.dp).background(Color(0xFF1A0F2B)).drawBehind {
          val p = ((fx.launch.value - 0.35f) / 0.6f).coerceIn(0f, 1f); val seg = size.width / 18f
          for (s in 0 until (p * 18).toInt()) drawRect(ArcCyan, Offset(s * seg + 2f, 3f), Size(seg - 4f, size.height - 6f))
        })
      }
    }
    Box(Modifier.align(Alignment.Center).graphicsLayer {
      val e = fx.enter.value; alpha = if (e in 0.15f..0.85f && ((e * 9f).toInt() % 2 == 0)) 1f else 0f
    }) { PixelText("PLAYER 1 READY", 30.sp, Bulb, depth = 4.dp) }
  }
}

@Composable
private fun PixelText(s: String, size: androidx.compose.ui.unit.TextUnit, color: Color, depth: androidx.compose.ui.unit.Dp = 2.dp) {
  Box {
    T(s, size, Modifier.offset(depth, depth), color = Color(0xFF1A0F2B), family = PressStart)
    T(s, size, color = color, family = PressStart)
  }
}

@Composable
private fun Cartridge(env: HomeEnv, items: List<StyleItem>, i: Int, drv: FocusDriver, fx: StyleFx, nav: StyleNav, t: State<Float>, attractK: Float) {
  val item = items[i]
  Box(Modifier.size(CartW, CartH)
    .graphicsLayer {
      val k = drv.k(i)
      // entry: carts drop into their slots with a little bounce
      val e = ((fx.enter.value - 0.12f - 0.03f * i.coerceAtMost(12)) / 0.5f).coerceIn(0f, 1f)
      val drop = 1f - (1f - e) * (1f - e)
      val bounce = kotlin.math.sin(e * Math.PI * 2.5).toFloat() * (1f - e) * 10.dp.toPx()
      val l = if (fx.launching == i) fx.launch.value else 0f
      translationY = -30.dp.toPx() * k + (1f - drop) * -90.dp.toPx() + bounce - 60.dp.toPx() * StyleDecel.transform((l / 0.3f).coerceIn(0f, 1f)) +
        attractK * kotlin.math.sin(t.value * 4f + i * 0.7f) * 6.dp.toPx()
      val s = 1f + 0.06f * k; scaleX = s; scaleY = s
      rotationX = -8f * k; cameraDistance = 12f * density
      alpha = drop
    }
    .drawWithCache {
      val body = Brush.verticalGradient(listOf(Color(0xFF3A3A46), Color(0xFF23232C)))
      val shape = Path().apply {
        val w = size.width; val h = size.height; val r = 8.dp.toPx(); val notch = 10.dp.toPx()
        moveTo(r, 0f); lineTo(w * 0.36f, 0f); lineTo(w * 0.36f + notch * 0.5f, notch * 0.6f); lineTo(w * 0.64f - notch * 0.5f, notch * 0.6f); lineTo(w * 0.64f, 0f)
        lineTo(w - r, 0f); quadraticBezierTo(w, 0f, w, r); lineTo(w, h); lineTo(0f, h); lineTo(0f, r); quadraticBezierTo(0f, 0f, r, 0f); close()
      }
      val m = 16.dp.toPx().toInt()
      val halo = SoftGlow.custom(size.width.toInt(), size.height.toInt(), m, 8.dp.toPx(), 0.7f)
      val glowTint = ColorFilter.tint(lighten(item.tint, 0.3f), BlendMode.SrcIn)
      onDrawBehind {
        val k = drv.k(i)
        if (k > 0.004f) drawImage(halo, Offset(-m.toFloat(), -m.toFloat()), alpha = k, colorFilter = glowTint)
        drawPath(shape, body)
        for (g in 0 until 5) drawRect(Color(0xFF15151B), Offset(size.width * 0.18f, 16.dp.toPx() + g * 4.dp.toPx()), Size(size.width * 0.64f, 1.6.dp.toPx()))
        drawPath(shape, Color(0x55FFFFFF), style = Stroke(1.dp.toPx()))
      }
    }
    .styleItem(env, items, i, drv, fx, nav)) {
    // label sticker: stripes from the app's own colours, its icon and name
    Box(Modifier.align(Alignment.BottomCenter).padding(start = 9.dp, end = 9.dp, bottom = 12.dp).fillMaxWidth().height(CartH * 0.58f).clip(RoundedCornerShape(4.dp)).drawWithCache {
      val a = lighten(item.tint, 0.25f); val b = darken(item.tint, 0.45f); val c = lighten(item.tint, 0.6f)
      val stripes = Path().apply { for (s in 0 until 6) { val x = s * size.width * 0.34f - size.width * 0.3f
        moveTo(x, size.height); lineTo(x + size.width * 0.14f, size.height); lineTo(x + size.width * 0.14f + size.height * 0.6f, 0f); lineTo(x + size.height * 0.6f, 0f); close() } }
      val gloss = Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color.Transparent), endY = size.height * 0.4f)
      onDrawBehind { drawRect(Brush.verticalGradient(listOf(a, b))); drawPath(stripes, c, alpha = 0.35f); drawRect(gloss) }
    }, Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ItemArt(item, Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)))
        Spacer(Modifier.height(6.dp))
        T(item.label.uppercase(), 6.5.sp, color = Color.White, family = PressStart, shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), 0f), maxLines = 1)
      }
    }
  }
}
