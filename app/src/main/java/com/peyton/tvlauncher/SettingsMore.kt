package com.peyton.tvlauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shared frame: options on the left, live preview of the home screen on the right. */
@Composable
private fun CustomizeFrame(st: LauncherState, title: String, sub: String, preview: @Composable () -> Unit, options: @Composable ColumnScope.() -> Unit) {
  Box(Modifier.fillMaxSize()) {
    Backdrop(st.backdrop)
    Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 40.dp, top = 40.dp, bottom = 24.dp)) {
      Column(Modifier.width(372.dp)) {
        T(title, 36.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
        T(sub, 13.sp, Modifier.padding(top = 4.dp, bottom = 10.dp), color = Ink.copy(alpha = 0.65f), maxLines = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = options)
      }
      Spacer(Modifier.width(28.dp))
      Column(Modifier.padding(top = 18.dp)) {
        T("LIVE PREVIEW", 11.sp, Modifier.padding(bottom = 8.dp, start = 4.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.55f), spacing = 2.sp)
        preview()
        T("BACK returns to Settings", 12.sp, Modifier.padding(top = 12.dp, start = 4.dp), color = Ink.copy(alpha = 0.55f))
      }
    }
  }
}

private fun <T> cycle(list: List<T>, cur: T, d: Int): T = list[(list.indexOf(cur).coerceAtLeast(0) + d + list.size) % list.size]

@Composable
fun LayoutSettings(st: LauncherState, actions: Actions) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(first) }
  var shown by remember { mutableStateOf(st.layout) }
  CustomizeFrame(st, "Home layout", "Move to preview a layout, press OK to use it.", {
    HomePreview(st, actions, shown, scale = 0.5f)
    T(shown.blurb, 13.5.sp, Modifier.padding(top = 10.dp, start = 4.dp).width(470.dp), color = Ink.copy(alpha = 0.8f), maxLines = 2)
  }) {
    HomeLayout.values().forEach { l ->
      SettingRow(l.title, if (st.layout == l) "✓  In use" else "Use", { st.changeLayout(l) }, fr = if (l == st.layout) first else null, onFocus = { shown = l })
    }
  }
}

@Composable
fun ClockSettings(st: LauncherState, actions: Actions) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(first) }
  val c = st.clock
  fun set(n: ClockLook) = st.changeClock(n)
  CustomizeFrame(st, "Clock style", "◀ ▶ changes a value; the preview updates live.", {
    HomePreview(st, actions, st.layout, scale = 0.5f)
    Box(Modifier.padding(top = 12.dp).width(480.dp).height(120.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x22FFFFFF)), Alignment.Center) {
      ClockFace(c, st.use24h, scale = if (c.style == ClockStyle.CORNER) 1.1f else if (c.style == ClockStyle.ANALOG) 0.5f else 0.62f, showDate = c.style != ClockStyle.ANALOG)
    }
  }) {
    val styles = ClockStyle.values().toList(); val fonts = ClockFont.values().toList(); val weights = ClockWeight.values().toList()
    SettingRow("Style", c.style.title, { set(c.copy(style = cycle(styles, c.style, 1))) }, { set(c.copy(style = cycle(styles, c.style, -1))) }, { set(c.copy(style = cycle(styles, c.style, 1))) }, first)
    SettingRow("Font", c.font.title, { set(c.copy(font = cycle(fonts, c.font, 1))) }, { set(c.copy(font = cycle(fonts, c.font, -1))) }, { set(c.copy(font = cycle(fonts, c.font, 1))) })
    SettingRow("Weight", if (c.font == ClockFont.DMSERIF) "Regular (only)" else c.weight.title, { set(c.copy(weight = cycle(weights, c.weight, 1))) },
      { set(c.copy(weight = cycle(weights, c.weight, -1))) }, { set(c.copy(weight = cycle(weights, c.weight, 1))) })
    SettingRow("Format", if (st.use24h) "24-hour" else "12-hour", { st.set24h(!st.use24h) }, { st.set24h(!st.use24h) }, { st.set24h(!st.use24h) })
    SettingRow("Use on ambient screen", if (c.inAmbient) "On" else "Off", { set(c.copy(inAmbient = !c.inAmbient)) }, { set(c.copy(inAmbient = !c.inAmbient)) }, { set(c.copy(inAmbient = !c.inAmbient)) })
    T("Fonts: Inter, Manrope, Outfit, Playfair Display, Space Grotesk, DM Serif Display (SIL Open Font License).", 11.5.sp, Modifier.padding(start = 20.dp, top = 10.dp), color = Ink.copy(alpha = 0.5f), maxLines = 3)
  }
}

@Composable
fun CardSettings(st: LauncherState, actions: Actions) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(50); tryFocus(first) }
  val slots = CardSlot.values().toList()
  fun setSlot(card: HomeCard, d: Int) { val m = LinkedHashMap(st.cards); m[card] = cycle(slots, st.cards[card] ?: CardSlot.OFF, d); st.changeCards(m) }
  CustomizeFrame(st, "Home cards", "◀ ▶ picks Off / Top-left / Top-right. Up to 2 cards per side.", {
    HomePreview(st, actions, st.layout, scale = 0.5f)
    val l = Look.inSlot(st.cards, CardSlot.LEFT).size; val r = Look.inSlot(st.cards, CardSlot.RIGHT).size
    T(when {
      st.layout == HomeLayout.MINIMAL -> "Minimal shows no cards. Pick another layout to see them."
      l > 2 || r > 2 -> "Only the first 2 cards of a side are shown."
      st.layout.ordinal > HomeLayout.MINIMAL.ordinal -> "${st.layout.title} shows the weather and the verse in its own way."
      st.layout != HomeLayout.GLASS -> "${st.layout.title} stacks both sides in one column."
      else -> "Layout: ${st.layout.title}"
    }, 13.sp, Modifier.padding(top = 10.dp, start = 4.dp).width(470.dp), color = Ink.copy(alpha = 0.75f), maxLines = 2)
  }) {
    HomeCard.values().forEachIndexed { i, card ->
      val slot = st.cards[card] ?: CardSlot.OFF
      SettingRow(card.title, slot.title, { setSlot(card, 1) }, { setSlot(card, -1) }, { setSlot(card, 1) }, if (i == 0) first else null)
    }
    T("COUNTDOWN", 12.sp, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.5f), spacing = 2.sp)
    val cd = st.countdown
    TextRow("Event", cd.title, "e.g. Mom's birthday") { st.changeCountdown(cd.copy(title = it.take(40))) }
    val months = listOf("—", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    fun mo(d: Int) = st.changeCountdown(cd.copy(month = ((cd.month + d) % 13 + 13) % 13, day = if (cd.day == 0) 1 else cd.day))
    fun dy(d: Int) = st.changeCountdown(cd.copy(day = (((cd.day - 1 + d) % 31 + 31) % 31) + 1, month = if (cd.month == 0) 1 else cd.month))
    SettingRow("Month", months[cd.month.coerceIn(0, 12)], { mo(1) }, { mo(-1) }, { mo(1) })
    SettingRow("Day", if (cd.day == 0) "—" else "${cd.day}", { dy(1) }, { dy(-1) }, { dy(1) })
    val yr = java.time.LocalDate.now().year
    val yOpts = listOf(0) + (yr..yr + 5)
    fun ye(d: Int) { val y = cycle(yOpts, if (cd.yearly) 0 else cd.year, d); st.changeCountdown(cd.copy(year = y, yearly = y == 0)) }
    SettingRow("Repeats", if (cd.yearly) "Every year" else "Once · ${cd.year}", { ye(1) }, { ye(-1) }, { ye(1) })
    T("NOTE", 12.sp, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.5f), spacing = 2.sp)
    TextRow("Note", st.note, "A short message for the home screen") { st.changeNote(it.take(140)) }
    T("Recent apps uses what you open from the launcher. For apps opened elsewhere too, allow usage access once with adb (see README).",
      11.5.sp, Modifier.padding(start = 20.dp, top = 12.dp), color = Ink.copy(alpha = 0.5f), maxLines = 3)
    Spacer(Modifier.height(30.dp))
  }
}

/** D-pad friendly text row: focus it, press OK, type with the TV keyboard. */
@Composable
fun TextRow(title: String, value: String, placeholder: String, onChange: (String) -> Unit) {
  var focused by remember { mutableStateOf(false) }
  val k by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "tr")
  val ink = lerpColor(Ink, Color(0xFF0B0D14), k)
  BasicTextField(value, onChange, singleLine = true,
    textStyle = TextStyle(color = ink, fontSize = 15.sp, fontFamily = Inter),
    cursorBrush = SolidColor(Color(0xFF2A55D4)),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged { focused = it.isFocused },
    decorationBox = { inner ->
      Row(Modifier.fillMaxWidth().graphicsLayer { scaleX = 1f + 0.015f * k; scaleY = 1f + 0.015f * k }.clip(RoundedCornerShape(16.dp))
        .background(lerpColor(Color(0x14FFFFFF), Color(0xF2FFFFFF), k)).padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, 17.sp, color = ink, weight = FontWeight.Medium)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f), Alignment.CenterEnd) {
          if (value.isEmpty()) T(placeholder, 15.sp, color = ink.copy(alpha = 0.5f))
          inner()
        }
      }
    })
}
