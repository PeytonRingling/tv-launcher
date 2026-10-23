package com.peyton.tvlauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ================================================================ 3) Magazine (editorial front page)

private val Paper = Color(0xFFF3EDE1)
private val PaperInk = Color(0xFF1C1A17)
private val InkSoft = Color(0xFF5C564D)
private val Red = Color(0xFFC2362B)
private val TocRow = 33.dp

@Composable
fun MagazineHome(env: HomeEnv) {
  val st = env.st
  val items = remember(st.favoriteApps) { styleItems(st, stylePrefix(HomeLayout.MAGAZINE)) }
  val drv = rememberFocusDriver(items.size)
  val fx = rememberStyleFx(env)
  val nav = remember(items.size) { StyleNav(items.size, 1) }
  VerseFums(st, env.actions)
  val now = rememberNow()
  val zdt = remember(now / 60_000) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()) }

  Box(Modifier.fillMaxSize().background(Color(0xFF15130F)).testTag("style-magazine")) {
    // The page: turns in from the spine (left edge) on entry and turns away on launch.
    Box(Modifier.fillMaxSize().graphicsLayer {
      val e = androidx.compose.animation.core.FastOutSlowInEasing.transform((fx.enter.value / 0.85f).coerceIn(0f, 1f))
      val l = StyleAccel.transform(fx.launch.value)
      transformOrigin = TransformOrigin(0f, 0.5f)
      cameraDistance = 9f * density * 160f / 72f * 10f
      rotationY = -84f * (1f - e) - 96f * l
    }.drawWithCache {
      val grain = Baked.get("paper", 192, 192) { x, y ->
        val n = pnoise(x / 3f, y / 3f, 64, 64, 3) * 0.6f + hash01(x * 193 + y, 9) * 0.4f   // 64 whole cells: tiles seamlessly
        Baked.argb(0.10f * n, 0.35f, 0.28f, 0.18f)
      }
      val shader = tiledBrush(grain)
      val spine = Brush.horizontalGradient(listOf(Color(0x33000000), Color.Transparent), endX = 40.dp.toPx())
      onDrawBehind { drawRect(Paper); drawRect(shader); drawRect(spine) }
    }) {
      MagazinePage(env, items, drv, fx, nav, zdt)
      // page shading while it turns (light falls off as the page tilts)
      Box(Modifier.fillMaxSize().drawBehind {
        val e = androidx.compose.animation.core.FastOutSlowInEasing.transform((fx.enter.value / 0.85f).coerceIn(0f, 1f))
        val a = maxOf(1f - e, fx.launch.value) * 0.55f
        if (a > 0.003f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black)), alpha = a)
      })
    }
  }
}

@Composable
private fun MagazinePage(env: HomeEnv, items: List<StyleItem>, drv: FocusDriver, fx: StyleFx, nav: StyleNav, zdt: java.time.ZonedDateTime) {
  val st = env.st
  val time = if (st.use24h) zdt.format(DateTimeFormatter.ofPattern("HH:mm")) else zdt.format(DateTimeFormatter.ofPattern("h:mm"))
  val ampm = if (st.use24h) "" else zdt.format(DateTimeFormatter.ofPattern("a", Locale.US)).lowercase()
  Column(Modifier.fillMaxSize().padding(start = 46.dp, end = 40.dp, top = 22.dp, bottom = 16.dp)) {
    // ---------------- masthead
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
      Column(Modifier.width(220.dp)) {
        T("No. ${zdt.dayOfYear}  ·  Vol. 4", 11.sp, color = InkSoft, spacing = 1.6.sp, family = Playfair, weight = FontWeight.Bold)
        T("Edition of $time $ampm", 11.sp, color = InkSoft, family = Playfair)
      }
      Box(Modifier.weight(1f), Alignment.Center) { T("The Living Room", 46.sp, color = PaperInk, family = DmSerif, spacing = (-0.5).sp) }
      Column(Modifier.width(220.dp), horizontalAlignment = Alignment.End) {
        T(zdt.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)).uppercase(), 11.sp, color = InkSoft, spacing = 1.6.sp, family = Playfair, weight = FontWeight.Bold)
        T(zdt.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)), 11.sp, color = InkSoft, family = Playfair)
      }
    }
    Rule(2.5.dp, 5.dp); Rule(0.8.dp, 2.dp)
    // dateline strip
    val wx = StyleData.weatherLine(st)
    T(listOfNotNull(wx?.let { "Weather: $it" }, StyleData.place(st), st.verse?.let { "Verse of the day: ${it.reference}" }, "${items.size - 2} apps in this issue")
      .joinToString("   \u2022   ").uppercase(), 10.sp, Modifier.padding(vertical = 5.dp).fillMaxWidth(), color = PaperInk, spacing = 1.3.sp, family = Playfair, weight = FontWeight.Bold,
      align = androidx.compose.ui.text.style.TextAlign.Center)
    Rule(0.8.dp, 0.dp)
    // ---------------- three columns with column rules
    Row(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
      // col 1: clock + pull quote
      Column(Modifier.width(318.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
          T(time, 104.sp, color = PaperInk, family = DmSerif, spacing = (-3).sp)
          T(ampm, 26.sp, Modifier.padding(start = 6.dp, bottom = 22.dp), color = Red, family = Playfair, weight = FontWeight.Bold)
        }
        T(remember(zdt.dayOfYear) { longDate(zdt) }, 15.sp, Modifier.padding(top = 0.dp), color = InkSoft, family = Playfair, maxLines = 2)
        Rule(0.8.dp, 10.dp)
        PullQuote(st)
      }
      ColumnRule()
      // col 2: hero photo + weather sidebar
      Column(Modifier.width(262.dp)) {
        val photo = st.photoOfDay ?: env.wall
        Box(Modifier.fillMaxWidth().height(168.dp).clipToBounds().background(Color(0xFFD9D1C2))) {
          if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
        }
        T("Photo of the day, from your library.", 11.5.sp, Modifier.padding(top = 5.dp), color = InkSoft, family = Playfair, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        WeatherSidebar(st)
      }
      ColumnRule()
      // col 3: table of contents (the apps)
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.Bottom) {
          T("In this issue", 22.sp, color = PaperInk, family = DmSerif)
          Spacer(Modifier.weight(1f))
          T("PAGE", 9.5.sp, Modifier.padding(bottom = 4.dp), color = InkSoft, spacing = 1.5.sp, family = Playfair, weight = FontWeight.Bold)
        }
        Rule(0.8.dp, 4.dp)
        // as many rows as the column has room for; the list scrolls so the focused entry stays 2 rows from the bottom
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
          val visible = (maxHeight / TocRow).toInt().coerceAtLeast(3)
          val scroll = { (drv.pos.value - (visible - 3)).coerceIn(0f, (items.size - visible).coerceAtLeast(0).toFloat()) }
          Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, (-(scroll() * TocRow.toPx())).roundToInt()) }) {
            items.forEachIndexed { i, item -> TocEntry(env, items, i, item, drv, fx, nav) }
          }
        }
      }
    }
    Rule(0.8.dp, 0.dp)
    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
      T("OK opens  \u00B7  hold OK for options  \u00B7  \u2193 past the last entry for every app", 10.sp, color = InkSoft, family = Playfair)
      Spacer(Modifier.weight(1f))
      T("\u2014 1 \u2014", 10.sp, color = InkSoft, family = Playfair)
    }
  }
}

private fun longDate(z: java.time.ZonedDateTime): String {
  val ord = listOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth",
    "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth", "twenty-first", "twenty-second", "twenty-third",
    "twenty-fourth", "twenty-fifth", "twenty-sixth", "twenty-seventh", "twenty-eighth", "twenty-ninth", "thirtieth", "thirty-first")
  return "${z.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))}, the ${ord[z.dayOfMonth]} of ${z.format(DateTimeFormatter.ofPattern("MMMM", Locale.US))}"
}

@Composable private fun Rule(h: androidx.compose.ui.unit.Dp, gapTop: androidx.compose.ui.unit.Dp) =
  Box(Modifier.padding(top = gapTop).fillMaxWidth().height(h).background(PaperInk))

@Composable private fun ColumnRule() = Box(Modifier.padding(horizontal = 18.dp).fillMaxHeight().width(0.8.dp).background(PaperInk.copy(alpha = 0.55f)))

/**
 * The verse as a pull quote with a real drop cap: the cap spans N body lines (3, or 2 for a short verse); its baseline
 * sits on the Nth line's baseline and its cap height reaches the first line's cap height, the first N lines wrap beside
 * it and the rest runs the full column width. A one-line verse gets no drop cap (a red initial in the running text).
 */
@Composable
private fun PullQuote(st: LauncherState) {
  val v = st.verse
  if (v == null) { T(if (!st.verseKey) NO_VERSE_KEY else "Today's verse is on its way.", 14.sp, Modifier.padding(top = 10.dp), color = InkSoft, family = Playfair, maxLines = 2); return }
  val body = v.text.trim()
  val size = when { body.length > 240 -> 13.5.sp; body.length > 160 -> 14.5.sp; body.length > 110 -> 16.sp; body.length > 40 -> 18.sp; else -> 24.sp }
  val style = TextStyle(fontFamily = Playfair, fontSize = size, fontStyle = FontStyle.Italic, lineHeight = size * 1.32f, color = PaperInk,
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None))
  val tm = rememberTextMeasurer(); val d = LocalDensity.current
  val colW = 318.dp
  class Plan(val lines: Int, val cap: androidx.compose.ui.text.TextLayoutResult?, val capW: Int, val split: Int, val head: androidx.compose.ui.text.TextLayoutResult?)
  val plan = remember(body, size) {
    val col = with(d) { colW.roundToPx() }; val gap = with(d) { 7.dp.roundToPx() }
    val lh = with(d) { (size * 1.32f).toPx() }; val bodyCap = with(d) { size.toPx() } * 0.708f
    val rest = body.drop(1)
    fun capFor(n: Int) = tm.measure(body.take(1), TextStyle(fontFamily = DmSerif, fontSize = with(d) { (((n - 1) * lh + bodyCap) / 0.66f).toSp() }, color = Red,
      lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)))
    var n = 3
    while (n >= 2) {
      val cap = capFor(n); val cw = cap.size.width + gap
      val r = tm.measure(rest, style, constraints = Constraints(maxWidth = col - cw))
      if (r.lineCount >= n || n == 2 && r.lineCount == 2) {
        val split = r.getLineEnd(n - 1, visibleEnd = true)
        return@remember Plan(n, cap, cw, split, r)
      }
      n--
    }
    Plan(1, null, 0, 0, null)
  }
  Column(Modifier.padding(top = 10.dp)) {
    if (plan.cap == null) {
      // too short for a drop cap: red initial inline
      androidx.compose.foundation.text.BasicText(androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Red, fontFamily = DmSerif, fontStyle = FontStyle.Normal, fontSize = size * 1.6f)); append(body.take(1)); pop(); append(body.drop(1))
      }, style = style)
    } else {
      val rest = body.drop(1)
      val head = plan.head!!; val cap = plan.cap
      val headH = with(d) { head.getLineBottom(plan.lines - 1).toDp() }
      val capTop = head.getLineTop(plan.lines - 1) + (head.firstBaseline - head.getLineTop(0)) - cap.firstBaseline
      Row(Modifier.height(headH)) {
        androidx.compose.foundation.Canvas(Modifier.width(with(d) { plan.capW.toDp() }).fillMaxHeight()) { drawText(cap, topLeft = Offset(-cap.getLineLeft(0).coerceAtMost(0f), capTop)) }
        androidx.compose.foundation.text.BasicText(rest.substring(0, plan.split).trimEnd(), style = style, maxLines = plan.lines)
      }
      if (plan.split < rest.length) androidx.compose.foundation.text.BasicText(rest.substring(plan.split).trimStart(), style = style, maxLines = 5,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    T("\u2014 ${v.reference}", 12.sp, Modifier.padding(top = 6.dp), color = Red, family = Playfair, weight = FontWeight.Bold, spacing = 0.8.sp)
    StyleData.notice(st)?.let { T(it, 9.5.sp, Modifier.padding(top = 2.dp), color = InkSoft, family = Playfair, maxLines = 2) }
  }
}

@Composable
private fun WeatherSidebar(st: LauncherState) {
  Column(Modifier.fillMaxWidth().background(Color(0x12000000)).padding(horizontal = 12.dp, vertical = 10.dp)) {
    T("THE FORECAST", 10.sp, color = Red, spacing = 1.8.sp, family = Playfair, weight = FontWeight.Bold)
    val w = st.weather
    if (w == null) { T(if (st.place == null) "Set your ZIP code in Settings." else "Awaiting the wire…", 13.sp, Modifier.padding(top = 4.dp), color = PaperInk, family = Playfair); return@Column }
    Row(verticalAlignment = Alignment.Bottom) {
      T(WeatherApi.fmt(w.tempF, st.celsius), 40.sp, color = PaperInk, family = DmSerif)
      Column(Modifier.padding(start = 10.dp, bottom = 8.dp)) {
        T(WeatherApi.describe(w.code), 13.sp, color = PaperInk, family = Playfair, weight = FontWeight.Bold)
        T("High ${WeatherApi.fmt(w.hiF, st.celsius)}, low ${WeatherApi.fmt(w.loF, st.celsius)}", 11.5.sp, color = InkSoft, family = Playfair)
      }
    }
    w.hours.take(4).forEach { h ->
      Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        T(h.label, 11.5.sp, color = PaperInk, family = Playfair)
        Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(1.dp).drawBehind {   // dotted leader
          var x = 0f; while (x < size.width) { drawCircle(InkSoft, 0.7.dp.toPx(), Offset(x, 0f)); x += 4.dp.toPx() }
        })
        T(WeatherApi.fmt(h.tempF, st.celsius), 11.5.sp, color = PaperInk, family = Playfair, weight = FontWeight.Bold)
      }
    }
    StyleData.place(st)?.let { T("Filed from $it", 10.sp, Modifier.padding(top = 3.dp), color = InkSoft, family = Playfair, maxLines = 1) }
  }
}

@Composable
private fun TocEntry(env: HomeEnv, items: List<StyleItem>, i: Int, item: StyleItem, drv: FocusDriver, fx: StyleFx, nav: StyleNav) {
  Box(Modifier.fillMaxWidth().height(TocRow).styleItem(env, items, i, drv, fx, nav).drawBehind {
    val k = drv.k(i)
    // marker bar on the left edge and a red underline that draws in from the left
    if (k > 0.005f) {
      drawRect(Red, Offset(0f, 6.dp.toPx()), Size(3.dp.toPx() * k, size.height - 12.dp.toPx()))
      drawRect(Red, Offset(30.dp.toPx(), size.height - 6.dp.toPx()), Size((size.width - 30.dp.toPx()) * k, 1.4.dp.toPx()))
    }
  }) {
    Row(Modifier.fillMaxSize().graphicsLayer { translationX = 8.dp.toPx() * drv.k(i) }, verticalAlignment = Alignment.CenterVertically) {
      T("%02d".format(i + 1), 15.sp, Modifier.width(30.dp), color = Red, family = DmSerif)
      T(item.label, 17.sp, color = PaperInk, family = Playfair, weight = FontWeight.Bold)
      Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp).drawBehind {
        var x = 0f; while (x < size.width) { drawCircle(InkSoft, 0.7.dp.toPx(), Offset(x, 0f)); x += 4.dp.toPx() }
      })
      T("${i + 1}", 13.sp, Modifier.padding(end = 4.dp), color = InkSoft, family = Playfair)
    }
  }
}
