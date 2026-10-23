package com.peyton.tvlauncher

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

// ================================================================ 8) Record shelf (vinyl reads better than spines at TV distance)

private val SleeveW = 166.dp
private val SleeveGap = 24.dp
private val LedgeY = 446.dp
private val Cream = Color(0xFFF2E6CF)
/** How far the disc stands out past its sleeve's right edge when fully out. */
private val DiscOut = SleeveW * 0.49f
/** Records to the right of the focused one step aside by this much, so the pulled-out disc never covers their art. */
private val Push = DiscOut - SleeveGap + 12.dp

/** Push of record [i] (eased exactly like the disc slide of the record before it, so the gap stays clear mid-move). */
private fun push(drv: FocusDriver, i: Int): Float = StyleDecel.transform((i - drv.pos.value).coerceIn(0f, 1f))
private val Brass = Color(0xFFC9A45C)

/**
 * Wood grain, baked once: stretched value noise + ring lines in warm browns. Every term is periodic over the bitmap
 * (whole lattice cells / whole ring cycles per tile), so the texture tiles with no visible seam.
 */
private fun woodGrain(key: String, w: Int, h: Int, vertical: Boolean, dark: Float) = Baked.get(key, w, h) { x, y ->
  val along = if (vertical) h else w; val across = if (vertical) w else h
  val u = if (vertical) y.toFloat() else x.toFloat(); val v = if (vertical) x.toFloat() else y.toFloat()
  fun cells(len: Int, scale: Float) = maxOf(1, kotlin.math.round(len / scale).toInt())
  val a1 = cells(along, 60f); val a2 = cells(along, 14f); val a3 = cells(along, 3f)
  val b1 = cells(across, 3.2f); val b2 = cells(across, 1.4f); val b3 = cells(across, 0.8f)
  val n = pnoise(u * a1 / along, v * b1 / across, a1, b1, 5) * 0.6f + pnoise(u * a2 / along, v * b2 / across, a2, b2, 6) * 0.25f +
    pnoise(u * a3 / along, v * b3 / across, a3, b3, 7) * 0.15f
  val rings = maxOf(1, kotlin.math.round(across * 0.9f / (2 * Math.PI.toFloat())).toInt())
  val ring = (kotlin.math.sin(2 * Math.PI.toFloat() * rings * v / across + n * 16.2f) * 0.5f + 0.5f).let { it * it }
  val l = (0.55f + 0.35f * n - 0.18f * ring) * dark
  Baked.argb(1f, 0.52f * l, 0.33f * l, 0.19f * l)
}

/** Vinyl, baked: fine grooves, a lead-in ring, and a soft anisotropic sheen. The centre label is drawn live in the app colour. */
private fun vinyl(px: Int) = Baked.get("vinyl", px, px) { x, y ->
  val c = px / 2f; val dx = x + 0.5f - c; val dy = y + 0.5f - c; val r = kotlin.math.hypot(dx, dy) / c
  if (r > 1f) 0 else {
    val groove = 0.07f + 0.035f * kotlin.math.sin(r * 260f) + if (r > 0.93f) 0.05f else 0f
    val ang = kotlin.math.atan2(dy, dx)
    val sheen = (kotlin.math.cos(2 * ang - 0.8f) * 0.5f + 0.5f).let { it * it * it } * 0.22f * (if (r > 0.36f) 1f else 0f)
    val edge = ((1f - r) * c).coerceIn(0f, 1f)
    Baked.argb(edge, groove + sheen, groove + sheen, groove + sheen * 1.05f)
  }
}

@Composable
fun ShelfHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.SHELF)) }
  val n = items.size
  val drv = rememberFocusDriver(n)
  val fx = rememberStyleFx(env)
  val t = rememberAmbientTime(env, 30)
  val nav = remember(n) { StyleNav(n, n) }
  VerseFums(st, env.actions)
  val now = rememberNow()
  // Scroll (in records) keeps the focused record ~1.4 slots in; at the end the last record *and* its pulled-out disc
  // stay on screen with a margin.
  val screenWdp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
  val stride = SleeveW.value + SleeveGap.value
  val maxScroll = ((n - 1) - (screenWdp - 36f - DiscOut.value - SleeveW.value - 64f) / stride).coerceAtLeast(0f)
  val scroll = { (drv.pos.value - 1.4f).coerceIn(0f, maxScroll) }

  Box(Modifier.fillMaxSize().background(Color(0xFF1A110C)).testTag("style-shelf")) {
    // ---------------- wall: vertical planks, warm lamp falloff, window light beam with dust motes
    Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
      val planks = woodGrain("wall", 256, 256, vertical = true, dark = 0.62f)
      val shader = tiledBrush(planks)
      val lamp = Brush.radialGradient(listOf(Color(0x33FFC98A), Color(0x14FFB070), Color.Transparent), Offset(size.width * 0.5f, size.height * 0.35f), size.width * 0.6f)
      val vign = Brush.radialGradient(listOf(Color.Transparent, Color(0x99000000)), Offset(size.width / 2, size.height * 0.45f), size.maxDimension * 0.7f)
      val seam = 96.dp.toPx()
      onDrawBehind {
        drawRect(shader)
        var x = seam * 0.5f; while (x < size.width) { drawRect(Color(0x66000000), Offset(x, 0f), Size(2.dp.toPx(), size.height)); drawRect(Color(0x14FFFFFF), Offset(x + 2.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)); x += seam }
        drawRect(lamp); drawRect(vign)
      }
    })
    Box(Modifier.fillMaxSize().drawWithCache {
      val beam = Path().apply { moveTo(size.width * 0.06f, 0f); lineTo(size.width * 0.30f, 0f); lineTo(size.width * 0.62f, size.height); lineTo(size.width * 0.22f, size.height); close() }
      val beamBrush = Brush.linearGradient(listOf(Color(0x30FFE2B0), Color(0x10FFE2B0), Color.Transparent), Offset(size.width * 0.18f, 0f), Offset(size.width * 0.42f, size.height))
      val mx = FloatArray(42) { hash01(it, 61) }; val my = FloatArray(42) { hash01(it, 62) }; val ms = FloatArray(42) { 0.6f + hash01(it, 63) }
      onDrawBehind {
        drawPath(beamBrush.let { beam }, beamBrush)
        val tt = t.value
        for (i in mx.indices) {
          // slow drift + wobble; only visible inside the beam
          val y = ((my[i] + tt * 0.006f * ms[i]) % 1f) * size.height
          val beamCx = size.width * (0.18f + 0.24f * y / size.height); val half = size.width * (0.12f + 0.08f * y / size.height)
          val x = beamCx + (mx[i] - 0.5f) * 2f * half + kotlin.math.sin(tt * 0.5f * ms[i] + i) * 10.dp.toPx()
          val inBeam = (1f - kotlin.math.abs(x - beamCx) / half).coerceIn(0f, 1f)
          val a = inBeam * (0.25f + 0.35f * kotlin.math.sin(tt * 1.3f * ms[i] + i * 2f).coerceAtLeast(0f))
          if (a > 0.01f) drawCircle(Color(0xFFFFEFD2), (0.8f + ms[i]).dp.toPx(), Offset(x, y), alpha = a)
        }
      }
    })
    // ---------------- wall pieces: clock plaque, weather tag, framed verse
    Column(Modifier.padding(start = 56.dp, top = 40.dp).graphicsLayer { val e = StyleDecel.transform((fx.enter.value / 0.5f).coerceIn(0f, 1f)); alpha = e }) {
      T("NOW PLAYING", 11.sp, color = Brass, weight = FontWeight.Bold, spacing = 3.sp, family = Playfair)
      Row(verticalAlignment = Alignment.Bottom) {
        T(fmtTime(now, st.use24h), 84.sp, color = Cream, family = DmSerif, shadow = Shadow(Color(0xAA000000), Offset(0f, 4f), 12f))
        if (!st.use24h) T(fmtAmPm(now).lowercase(), 22.sp, Modifier.padding(start = 6.dp, bottom = 18.dp), color = Brass, family = Playfair, weight = FontWeight.Bold)
      }
      T(java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(java.util.Date(now)), 17.sp, color = Cream.copy(alpha = 0.85f), family = Playfair)
      val w = st.weather
      Row(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE9DCC0)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF8A6A3A)))
        Spacer(Modifier.width(9.dp))
        T(if (w == null) (if (st.place == null) "Set your ZIP in Settings" else "Forecast coming…") else "${WeatherApi.fmt(w.tempF, st.celsius)}  ${WeatherApi.describe(w.code)}" + (StyleData.place(st)?.let { "  ·  $it" } ?: ""),
          13.sp, color = Color(0xFF3A2A18), family = Playfair, weight = FontWeight.Bold)
      }
    }
    st.verse?.let { v ->
      Box(Modifier.align(Alignment.TopEnd).padding(end = 56.dp, top = 36.dp).width(300.dp).graphicsLayer {
        val e = StyleDecel.transform(((fx.enter.value - 0.1f) / 0.5f).coerceIn(0f, 1f)); alpha = e; rotationZ = 1.2f
      }.drawWithCache {
        val frame = woodGrain("frame", 128, 32, vertical = false, dark = 0.5f)
        val fb = tiledBrush(frame)
        onDrawBehind {
          drawRect(Color(0x66000000), Offset(6.dp.toPx(), 8.dp.toPx()), size)   // drop shadow on the wall
          drawRect(fb); drawRect(Cream, Offset(10.dp.toPx(), 10.dp.toPx()), Size(size.width - 20.dp.toPx(), size.height - 20.dp.toPx()))
          drawRect(Color(0x33000000), Offset(10.dp.toPx(), 10.dp.toPx()), Size(size.width - 20.dp.toPx(), size.height - 20.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
      }.padding(26.dp)) {
        Column {
          androidx.compose.foundation.text.BasicText(v.text, style = androidx.compose.ui.text.TextStyle(fontFamily = Playfair, fontStyle = FontStyle.Italic,
            fontSize = if (v.text.length > 150) 12.sp else 14.sp, lineHeight = if (v.text.length > 150) 16.sp else 19.sp, color = Color(0xFF2B2016)), maxLines = 8)
          T(v.reference, 11.sp, Modifier.padding(top = 6.dp), color = Color(0xFF8A5A2B), weight = FontWeight.Bold, spacing = 1.5.sp, family = Playfair)
          StyleData.notice(st)?.let { T(it, 8.5.sp, color = Color(0xFF6B5A45), family = Playfair, maxLines = 2) }
        }
      }
    }
    // ---------------- the ledge
    Box(Modifier.align(Alignment.TopStart).padding(top = LedgeY).fillMaxWidth().height(30.dp).drawWithCache {
      val wood = tiledBrush(woodGrain("ledge", 512, 48, vertical = false, dark = 0.95f))
      val top = Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color.Transparent), endY = 5.dp.toPx())
      onDrawBehind {
        drawRect(Color(0x88000000), Offset(0f, size.height), Size(size.width, 18.dp.toPx()))
        drawRect(wood); drawRect(top); drawRect(Color(0x55000000), Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
      }
    })
    // ---------------- the records
    Row(Modifier.align(Alignment.TopStart).padding(top = LedgeY - SleeveW - 2.dp).wrapContentWidth(Alignment.Start, unbounded = true)
      .padding(start = 64.dp).offset { IntOffset((-(scroll() * (SleeveW + SleeveGap).toPx())).roundToInt(), 0) }, horizontalArrangement = Arrangement.spacedBy(SleeveGap)) {
      items.forEachIndexed { i, item -> Record(env, items, i, item, drv, fx, nav, t) }
    }
    // brass name plates on the ledge front, under each record
    Row(Modifier.align(Alignment.TopStart).padding(top = LedgeY + 7.dp).wrapContentWidth(Alignment.Start, unbounded = true)
      .padding(start = 64.dp).offset { IntOffset((-(scroll() * (SleeveW + SleeveGap).toPx())).roundToInt(), 0) }, horizontalArrangement = Arrangement.spacedBy(SleeveGap)) {
      items.forEachIndexed { i, item ->
        Box(Modifier.width(SleeveW).graphicsLayer { translationX = Push.toPx() * push(drv, i) }, Alignment.Center) {
          Box(Modifier.clip(RoundedCornerShape(2.dp)).background(Brush.verticalGradient(listOf(Color(0xFFE4C989), Brass, Color(0xFF8E6E32)))).padding(horizontal = 8.dp, vertical = 1.dp)
            .graphicsLayer { alpha = 0.75f + 0.25f * drv.k(i) }) {
            T(item.label.uppercase(), 10.sp, color = Color(0xFF3A2A12), weight = FontWeight.Bold, spacing = 1.2.sp, family = Playfair)
          }
        }
      }
    }
    T("\u25C0 \u25B6 browse   ·   OK play   ·   hold OK for options", 11.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), color = Cream.copy(alpha = 0.5f), family = Playfair)
    // ---------------- launch: the record leaves the sleeve, grows and spins up to centre, then the room goes dark
    if (fx.launching >= 0) Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = ((fx.launch.value - 0.6f) / 0.4f).coerceIn(0f, 1f)) })
  }
}

@Composable
private fun Record(env: HomeEnv, items: List<StyleItem>, i: Int, item: StyleItem, drv: FocusDriver, fx: StyleFx, nav: StyleNav, t: State<Float>) {
  Box(Modifier.zIndex(if (drv.focused == i) 2f else 0f).size(SleeveW).graphicsLayer {
    // entry: records slide onto the shelf from the right, staggered
    val e = StyleDecel.transform(stagger(fx.enter.value, 0.15f, 0.045f, i, 0.5f))
    translationX = (1f - e) * 420.dp.toPx() + Push.toPx() * push(drv, i); alpha = e
  }) {
    // the disc, behind the sleeve: slides out to the right as focus arrives and spins while it's out
    Box(Modifier.size(SleeveW * 0.94f).align(Alignment.Center).graphicsLayer {
      val k = drv.k(i); val l = if (fx.launching == i) StyleAccel.transform(fx.launch.value) else 0f
      translationX = (DiscOut.toPx() + SleeveW.toPx() * 0.03f) * StyleDecel.transform(k) + SleeveW.toPx() * 1.2f * l
      translationY = -18.dp.toPx() * k - 90.dp.toPx() * l
      val s = 1f + 1.6f * l; scaleX = s; scaleY = s
      rotationZ = if (k > 0.01f || l > 0f) (t.value * 200f + l * 900f) % 360f else 0f
      alpha = if (k > 0.002f || l > 0f) 1f else 0f
    }.drawWithCache {
      val disc = vinyl(256)
      val label = lighten(item.tint, 0.1f)
      onDrawBehind {
        drawImage(disc, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(label, size.width * 0.17f, c)
        drawCircle(Color(0x33000000), size.width * 0.17f, c, style = Stroke(1.dp.toPx()))
        drawArc(Color(0x66FFFFFF), 200f, 60f, false, Offset(c.x - size.width * 0.12f, c.y - size.width * 0.12f), Size(size.width * 0.24f, size.width * 0.24f), style = Stroke(1.2.dp.toPx()))
        drawCircle(Color(0xFF1A110C), size.width * 0.018f, c)
      }
    })
    // the sleeve: lifts, grows and tilts toward you; embossed spine, ring wear and a printed corner
    Box(Modifier.fillMaxSize().graphicsLayer {
      val k = drv.k(i)
      transformOrigin = TransformOrigin(0.5f, 1f)
      val s = 1f + 0.08f * k; scaleX = s; scaleY = s
      translationY = -18.dp.toPx() * k
      rotationY = -10f * k; cameraDistance = 14f * density
    }.drawWithCache {
      val m = 16.dp.toPx().toInt()
      val shadow = SoftGlow.custom(size.width.toInt(), size.height.toInt(), m, 2.dp.toPx(), 0.6f, black = true)
      onDrawBehind {
        val k = drv.k(i)
        drawImage(shadow, Offset(-m.toFloat() + 6.dp.toPx() * (1 + k), -m.toFloat() + 4.dp.toPx() * (1 + k)), alpha = 0.55f + 0.3f * k)
      }
    }.styleItem(env, items, i, drv, fx, nav).clip(RoundedCornerShape(2.dp)).drawWithCache {
      val spine = Brush.horizontalGradient(listOf(Color(0x55FFFFFF), Color(0x22000000), Color.Transparent), endX = 9.dp.toPx())
      val wear = Stroke(1.2.dp.toPx())
      val gloss = Brush.linearGradient(listOf(Color(0x26FFFFFF), Color.Transparent), Offset.Zero, Offset(size.width * 0.6f, size.height * 0.6f))
      onDrawWithContent {
        drawContent()
        drawCircle(Color(0x1FFFFFFF), size.width * 0.44f, Offset(size.width / 2, size.height / 2), style = wear)   // ring wear from the record inside
        drawRect(spine); drawRect(gloss)
        drawRect(Color(0x40000000), style = Stroke(1.dp.toPx()))
        val k = drv.k(i); if (k > 0.01f) drawRect(Cream, alpha = k * 0.9f, style = Stroke(2.5.dp.toPx()))
      }
    }) {
      ItemArt(item, Modifier.fillMaxSize())
      Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xCCF2E6CF)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        T("33\u2153", 8.sp, color = Color(0xFF3A2A18), weight = FontWeight.Bold, family = Playfair)
      }
    }
  }
}
