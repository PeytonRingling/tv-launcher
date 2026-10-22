package com.peyton.tvlauncher

import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

private val CardDark = Color(0xFF101115)
const val NO_ZIP = "Set your ZIP code in Settings › Weather location to see the forecast."
const val NO_VERSE_KEY = "Add your API.Bible key in Settings."
private val CardShape = RoundedCornerShape(24.dp)

/** Vertical stack of glass cards for one slot. */
@Composable
fun CardStack(cards: List<HomeCard>, st: LauncherState, actions: Actions, canFocus: Boolean, width: Dp, modifier: Modifier = Modifier, max: Int = 2) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    cards.take(max).forEach { c -> key(c) { HomeCardView(c, st, actions, canFocus, width) } }
  }
}

@Composable
fun HomeCardView(card: HomeCard, st: LauncherState, actions: Actions, canFocus: Boolean, width: Dp) {
  when (card) {
    HomeCard.WEATHER -> if (st.place == null) Placeholder("WEATHER", NO_ZIP, width)
      else st.weather?.let { WeatherCard(it, st.celsius, Modifier.width(width), st.place!!.label) } ?: Placeholder("WEATHER", "Waiting for the forecast…", width)
    HomeCard.WEATHER3 -> if (st.place == null) Placeholder("3-DAY", NO_ZIP, width)
      else st.weather?.let { Weather3Card(it, st.celsius, width, st.place!!.label) } ?: Placeholder("3-DAY", "Waiting for the forecast…", width)
    HomeCard.INPUTS -> InputsCard(st.inputs, canFocus, { st.inputsOpen = true }, Modifier.width(width))
    HomeCard.DATE -> DateCard(width)
    HomeCard.RECENT -> RecentCard(st, actions, canFocus, width)
    HomeCard.PHOTO -> PhotoCard(st, actions, canFocus, width)
    HomeCard.TOGGLES -> TogglesCard(st, actions, canFocus, width)
    HomeCard.COUNTDOWN -> CountdownCard(st.countdown, width)
    HomeCard.NOTE -> NoteCard(st.note, width)
    HomeCard.VERSE -> VerseCard(st, actions, width)
  }
}

@Composable
private fun CardTitle(t: String, m: Modifier = Modifier) =
  T(t, 10.5.sp, m.padding(bottom = 6.dp), weight = FontWeight.Bold, color = Ink.copy(alpha = 0.90f), spacing = 1.6.sp, shadow = TextShadow)

@Composable
private fun Placeholder(title: String, msg: String, width: Dp) {
  Column(Modifier.width(width).frosted(CardShape).padding(horizontal = 18.dp, vertical = 15.dp)) {
    CardTitle(title); T(msg, 13.sp, color = Ink.copy(alpha = 0.8f), shadow = TextShadow, maxLines = 3)
  }
}

@Composable
fun WeatherCard(w: Weather, celsius: Boolean, modifier: Modifier, place: String) {
  Column(modifier.frosted(CardShape).padding(horizontal = 18.dp, vertical = 15.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Image(rememberVectorPainter(Icons.Filled.LocationOn), null, Modifier.size(13.dp), colorFilter = ColorFilter.tint(Ink.copy(alpha = 0.92f)))
      Spacer(Modifier.width(4.dp))
      T(place, 12.sp, weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.92f), shadow = TextShadow)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
      T(WeatherApi.fmt(w.tempF, celsius), 46.sp, weight = FontWeight.Light, spacing = (-2).sp, shadow = TextShadow)
      Spacer(Modifier.width(10.dp))
      Column {
        T(WeatherApi.describe(w.code), 13.sp, weight = FontWeight.SemiBold, shadow = TextShadow)
        T("H ${WeatherApi.fmt(w.hiF, celsius)}  ·  L ${WeatherApi.fmt(w.loF, celsius)}", 11.5.sp, Modifier.padding(top = 2.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.88f), shadow = TextShadow)
      }
    }
    if (w.hours.isNotEmpty()) {
      Box(Modifier.padding(top = 9.dp, bottom = 9.dp).fillMaxWidth().height(1.dp).background(Color(0x40FFFFFF)))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        w.hours.take(5).forEach { h ->
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(h.label, 10.5.sp, weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.88f), shadow = TextShadow)
            Spacer(Modifier.height(4.dp))
            WeatherGlyph(WeatherApi.sky(h.code), h.isDay, 17.dp)
            Spacer(Modifier.height(3.dp))
            T(WeatherApi.fmt(h.tempF, celsius), 12.5.sp, weight = FontWeight.SemiBold, shadow = TextShadow)
          }
        }
      }
    }
  }
}

@Composable
private fun Weather3Card(w: Weather, celsius: Boolean, width: Dp, place: String) {
  val days = w.days.ifEmpty { listOf(Day("Today", w.code, w.hiF, w.loF)) }.take(4)
  val lo = days.minOf { it.loF }; val hi = days.maxOf { it.hiF }; val span = (hi - lo).coerceAtLeast(1.0)
  Column(Modifier.width(width).frosted(CardShape).padding(horizontal = 18.dp, vertical = 14.dp)) {
    CardTitle("FORECAST · ${place.uppercase()}")
    days.forEach { d ->
      Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        T(d.label, 12.5.sp, Modifier.width(48.dp), weight = FontWeight.SemiBold, shadow = TextShadow)
        WeatherGlyph(WeatherApi.sky(d.code), true, 18.dp)
        Spacer(Modifier.width(8.dp))
        T(WeatherApi.fmt(d.loF, celsius), 12.sp, Modifier.width(30.dp), weight = FontWeight.Medium, color = Ink.copy(alpha = 0.8f), shadow = TextShadow)
        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x30FFFFFF))) {
          val a = ((d.loF - lo) / span).toFloat(); val b = ((d.hiF - lo) / span).toFloat()
          Box(Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer {
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f); scaleX = (b - a).coerceAtLeast(0.08f); translationX = a * size.width
          }.background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF9DD0FF), Color(0xFFFFC46B)))))
        }
        T(WeatherApi.fmt(d.hiF, celsius), 12.sp, Modifier.width(34.dp).padding(start = 6.dp), weight = FontWeight.SemiBold, shadow = TextShadow)
      }
    }
  }
}

@Composable
fun InputsCard(inputs: List<InputUi>, canFocus: Boolean, onMore: () -> Unit, modifier: Modifier) {
  Column(modifier.frosted(CardShape).padding(horizontal = 9.dp, vertical = 13.dp)) {
    CardTitle("INPUTS", Modifier.padding(start = 9.dp))
    val shown = if (inputs.size > 3) inputs.take(2) else inputs
    shown.forEach { InputRow(it.short, it.label, it.sub, canFocus, it.action) }
    if (inputs.size > 3) InputRow("+${inputs.size - 2}", "All inputs", "Live TV & more", canFocus, onMore)
    if (inputs.isEmpty()) InputRow("TV", "Inputs", "Live TV & sources", canFocus, onMore)
  }
}

@Composable
fun InputRow(short: String, label: String, sub: String, canFocus: Boolean, onClick: () -> Unit, icon: (@Composable () -> Unit)? = null) {
  var f by remember { mutableStateOf(false) }
  val k by animateFloatAsState(if (f) 1f else 0f, tween(150), label = "ir")
  Row(
    Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
      .onFocusChanged { f = it.isFocused }
      .dpadClick(onClick)
      .focusProperties { this.canFocus = canFocus }
      .focusable()
      .clip(RoundedCornerShape(14.dp))
      .background(Color.White.copy(alpha = 0.94f * k))
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val c = lerpColor(Ink, CardDark, k)
    if (icon != null) icon() else Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(lerpColor(Color(0x38FFFFFF), Color(0xFF1C1D22), k)), Alignment.Center) {
      T(short, 9.5.sp, weight = FontWeight.Bold, color = Ink)
    }
    Spacer(Modifier.width(10.dp))
    Column {
      T(label, 12.5.sp, weight = FontWeight.SemiBold, color = c, shadow = if (k < 0.5f) TextShadow else null)
      T(sub, 10.sp, weight = FontWeight.Medium, color = c.copy(alpha = 0.84f), shadow = if (k < 0.5f) TextShadow else null)
    }
  }
}

@Composable
private fun DateCard(width: Dp) {
  val now = rememberNow()
  val d = remember(now / 60_000) { java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
  Row(Modifier.width(width).frosted(CardShape).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    T("${d.dayOfMonth}", 58.sp, weight = FontWeight.Light, spacing = (-2).sp, shadow = TextShadow)
    Spacer(Modifier.width(14.dp))
    Column {
      T(d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US), 16.sp, weight = FontWeight.SemiBold, shadow = TextShadow)
      T("${d.month.getDisplayName(TextStyle.FULL, Locale.US)} ${d.year}", 13.sp, weight = FontWeight.Medium, color = Ink.copy(alpha = 0.88f), shadow = TextShadow)
      T("Week ${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}  ·  Day ${d.dayOfYear}", 11.sp, Modifier.padding(top = 3.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.72f), shadow = TextShadow)
    }
  }
}

@Composable
private fun RecentCard(st: LauncherState, actions: Actions, canFocus: Boolean, width: Dp) {
  val byPkg = remember(st.apps) { st.apps.associateBy { it.pkg } }
  val list = remember(st.recents, byPkg, st.hidden) { st.recents.mapNotNull { byPkg[it] }.filter { it.pkg !in st.hidden }.take(3) }
  Column(Modifier.width(width).frosted(CardShape).padding(horizontal = 9.dp, vertical = 13.dp)) {
    CardTitle(if (st.usageAccess) "RECENTLY WATCHED" else "RECENT", Modifier.padding(start = 9.dp))
    if (list.isEmpty()) T("Apps you open show up here.", 12.sp, Modifier.padding(start = 9.dp, bottom = 4.dp), color = Ink.copy(alpha = 0.8f), shadow = TextShadow, maxLines = 2)
    list.forEachIndexed { i, app ->
      InputRow("", app.label, if (i == 0) "Last opened" else "Recent", canFocus, { actions.launch(app) }) { UniformIcon(app, 28.dp) }
    }
  }
}

@Composable
private fun PhotoCard(st: LauncherState, actions: Actions, canFocus: Boolean, width: Dp) {
  var f by remember { mutableStateOf(false) }
  val k = animateFloatAsState(if (f) 1f else 0f, tween(160), label = "pc")
  Column(Modifier.width(width)
    .graphicsLayer { val s = 1f + 0.03f * k.value; scaleX = s; scaleY = s }
    .onFocusChanged { f = it.isFocused }.dpadClick({ actions.startAmbient() }).focusProperties { this.canFocus = canFocus }.focusable()
    .frosted(CardShape).padding(8.dp)) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(17.dp)).background(Color(0x22FFFFFF))) {
      st.photoOfDay?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
      Box(Modifier.fillMaxSize().drawOutlineWhenFocused(k))
    }
    Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      T("PHOTO OF THE DAY", 10.5.sp, weight = FontWeight.Bold, color = Ink.copy(alpha = 0.9f), spacing = 1.4.sp, shadow = TextShadow)
      Spacer(Modifier.weight(1f))
      T("OK  ▸  Ambient", 10.sp, Modifier.padding(end = 6.dp).graphicsLayer { alpha = k.value }, weight = FontWeight.SemiBold, color = Accent)
    }
  }
}

private fun Modifier.drawOutlineWhenFocused(k: State<Float>) = this.drawWithContent {
  drawContent()
  if (k.value > 0.01f) drawRoundRect(Color.White, alpha = k.value, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(17.dp.toPx()))
}

@Composable
private fun TogglesCard(st: LauncherState, actions: Actions, canFocus: Boolean, width: Dp) {
  Column(Modifier.width(width).frosted(CardShape).padding(horizontal = 12.dp, vertical = 13.dp)) {
    CardTitle("QUICK", Modifier.padding(start = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      ToggleButton(Icons.Filled.Star, "Ambient", canFocus) { actions.startAmbient() }
      ToggleButton(Icons.Filled.Settings, "Settings", canFocus) { st.screen = Screen.SETTINGS }
      ToggleButton(Icons.Filled.PlayArrow, "Inputs", canFocus) { st.inputsOpen = true }
    }
  }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, canFocus: Boolean, onClick: () -> Unit) {
  var f by remember { mutableStateOf(false) }
  val k by animateFloatAsState(if (f) 1f else 0f, tween(150), label = "tb")
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(52.dp).graphicsLayer { val s = 1f + 0.08f * k; scaleX = s; scaleY = s }
      .onFocusChanged { f = it.isFocused }.dpadClick(onClick).focusProperties { this.canFocus = canFocus }.focusable()
      .clip(RoundedCornerShape(17.dp)).background(lerpColor(Color(0x30FFFFFF), Color(0xF2FFFFFF), k)), Alignment.Center) {
      Image(rememberVectorPainter(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(lerpColor(Ink, CardDark, k)))
    }
    T(label, 11.sp, Modifier.padding(top = 5.dp), weight = FontWeight.SemiBold, shadow = TextShadow)
  }
}

@Composable
private fun CountdownCard(c: Countdown, width: Dp) {
  val now = rememberNow()
  val today = remember(now / 3_600_000) { LocalDate.now() }
  val left = c.daysLeft(today); val next = c.next(today)
  Column(Modifier.width(width).frosted(CardShape).padding(horizontal = 18.dp, vertical = 14.dp)) {
    CardTitle("COUNTDOWN")
    if (left == null || next == null) { T("Set a date in Settings → Home cards.", 13.sp, color = Ink.copy(alpha = 0.85f), shadow = TextShadow, maxLines = 2); return@Column }
    Row(verticalAlignment = Alignment.Bottom) {
      T(when (left) { 0L -> "Today"; 1L -> "1"; else -> "$left" }, 44.sp, weight = FontWeight.Light, spacing = (-1.5).sp, shadow = TextShadow)
      if (left > 0) T(if (left == 1L) "day" else "days", 15.sp, Modifier.padding(start = 6.dp, bottom = 9.dp), weight = FontWeight.SemiBold, shadow = TextShadow)
    }
    T(if (left == 0L) c.title.ifBlank { "It's the day!" } else "until ${c.title.ifBlank { "the big day" }}", 13.5.sp, weight = FontWeight.SemiBold, shadow = TextShadow, maxLines = 2)
    T("${next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)}, ${next.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${next.dayOfMonth}${if (next.year != today.year) ", ${next.year}" else ""}",
      11.5.sp, Modifier.padding(top = 2.dp), weight = FontWeight.Medium, color = Ink.copy(alpha = 0.78f), shadow = TextShadow)
  }
}

@Composable
private fun NoteCard(text: String, width: Dp) {
  Column(Modifier.width(width).frosted(CardShape).padding(horizontal = 18.dp, vertical = 14.dp)) {
    CardTitle("NOTE")
    T(text.ifBlank { "Write a note in Settings → Home cards." }, if (text.length < 40) 18.sp else 14.sp, weight = if (text.isBlank()) FontWeight.Normal else FontWeight.Medium,
      color = if (text.isBlank()) Ink.copy(alpha = 0.8f) else Ink, shadow = TextShadow, maxLines = 5)
  }
}

/** What the verse card shows; equal values never animate, so a refresh that returns the same text changes nothing on screen. */
private data class VerseShown(val reference: String, val text: String, val copyright: String, val abbr: String)

/**
 * Verse of the day. The cached verse is in [LauncherState.verse] before the first frame (no pop-in); when the day or the
 * text changes it crossfades. Plain, non-focusable text: nothing to select, copy or share.
 */
@Composable
private fun VerseCard(st: LauncherState, actions: Actions, width: Dp) {
  val v = st.verse
  // FUMS: report each time the verse is on screen (new verse, or the launcher came back to the foreground).
  val token = v?.fums
  LaunchedEffect(token, st.verseSession) { if (token != null) actions.reportFums(token) }
  val view: Any = when {
    v != null -> VerseShown(v.reference, v.text, v.copyright, st.verseAbbr)
    !st.verseKey -> NO_VERSE_KEY
    else -> (st.verseStatus ?: VerseStatus.LOADING).message
  }
  androidx.compose.animation.AnimatedContent(view, transitionSpec = {
    (androidx.compose.animation.fadeIn(tween(520, delayMillis = 120)) togetherWith androidx.compose.animation.fadeOut(tween(260)))
      .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> tween(420) })
  }, label = "verse") { s ->
    if (s is VerseShown) VerseBody(s, st.verseNotice, width) else Placeholder("VERSE OF THE DAY", s as String, width)
  }
}

@Composable
private fun VerseBody(s: VerseShown, showNotice: Boolean, width: Dp) {
  // Toggling the notice: it fades while the card's height eases (animateContentSize), so nothing jumps.
  val k by animateFloatAsState(if (showNotice) 1f else 0f, tween(260), label = "notice")
  Column(Modifier.width(width).frosted(CardShape).animateContentSize(tween(320)).padding(horizontal = 18.dp, vertical = 15.dp)) {
    CardTitle("VERSE OF THE DAY")
    val n = s.text.length
    // Sized to fit without truncation (no fine print on the card): up to VerseText.MAX_CHARS fits at 14sp.
    val size = when { n <= 100 -> 16.5.sp; n <= 160 -> 15.sp; n <= VerseText.MAX_CHARS -> 14.sp; else -> 12.5.sp }
    T(s.text, size, weight = FontWeight.Medium, shadow = TextShadow, maxLines = 16)
    // Reference, then (setting on) the short-form notice "· NIV © 2011 Biblica, Inc. All rights reserved." (API.Bible Terms,
    // Appendix A). The full copyright line and "Powered by API.Bible" are always in Settings › Verse of the day.
    val notice = remember(s) { VerseText.shortNotice(s.abbr, s.copyright).replace(" ", "\u00A0")
      .replace("\u00A0©\u00A0", " ©\u00A0").replace(".\u00A0All", ". All") }   // wrap only before © or "All rights reserved."
    val noticeOn = showNotice || k > 0.01f
    androidx.compose.foundation.text.BasicText(
      androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Ink.copy(alpha = 0.95f), shadow = TextShadow))
        append(s.reference.replace(" ", "\u00A0")); pop()
        if (noticeOn) {
          pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Medium, fontSize = 10.5.sp, color = Ink.copy(alpha = 0.72f * k),
            shadow = TextShadow.copy(color = TextShadow.color.copy(alpha = TextShadow.color.alpha * k))))
          append("  ·  "); append(notice); pop()
        }
      },
      Modifier.padding(top = 9.dp),
      style = androidx.compose.ui.text.TextStyle(fontFamily = Inter, lineHeight = 15.sp,
        fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.None),
      maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    )
  }
}
