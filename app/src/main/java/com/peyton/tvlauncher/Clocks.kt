package com.peyton.tvlauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val ClockGlow = Shadow(Color(0x66000000), Offset(0f, 6f), 34f)

/**
 * One clock component used by every layout and (optionally) the ambient screen.
 * [scale] 1 = hero size on the Glass layout; layouts shrink it. Ticks once a second but is isolated, so only
 * this small subtree recomposes.
 */
@Composable
fun ClockFace(look: ClockLook, use24h: Boolean, modifier: Modifier = Modifier, scale: Float = 1f,
              align: Alignment.Horizontal = Alignment.CenterHorizontally, showDate: Boolean = true, nowOverride: Long? = null) {
  val now = nowOverride ?: rememberNow()
  val fam = ClockFonts.family(look.font)
  val w = look.weight.fw
  val serif = look.font == ClockFont.PLAYFAIR || look.font == ClockFont.DMSERIF
  fun track(size: Float) = (if (serif) -0.015f * size else -0.045f * size).sp
  val ta = when (align) { Alignment.Start -> TextAlign.Start; Alignment.End -> TextAlign.End; else -> TextAlign.Center }
  val cal = Calendar.getInstance().apply { timeInMillis = now }
  val time = fmtTime(now, use24h)
  val date: @Composable (Float) -> Unit = { sz ->
    if (showDate) T(fmtDate(now), (sz * scale).sp, Modifier.offset(y = (-4 * scale).dp), weight = FontWeight.SemiBold, color = Ink, spacing = 0.3.sp, shadow = ClockGlow, align = ta)
  }
  when (look.style) {
    ClockStyle.CENTERED -> Column(modifier, horizontalAlignment = align) {
      val sz = 128f * scale
      T(time, sz.sp, weight = w, spacing = track(sz), shadow = ClockGlow, family = fam)
      date(17f)
    }
    ClockStyle.STACKED -> Column(modifier, horizontalAlignment = align) {
      val sz = 104f * scale
      T(time, sz.sp, weight = w, spacing = track(sz), shadow = ClockGlow, family = fam)
      if (showDate) {
        T(java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(java.util.Date(now)), (24 * scale).sp, Modifier.offset(y = (-6 * scale).dp), weight = FontWeight.Bold, shadow = ClockGlow, family = fam)
        T(java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).format(java.util.Date(now)), (17 * scale).sp, Modifier.offset(y = (-4 * scale).dp), weight = FontWeight.Medium, color = Ink.copy(alpha = 0.86f), shadow = ClockGlow)
      }
    }
    ClockStyle.CORNER -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
      val sz = 44f * scale
      T(time, sz.sp, weight = w, spacing = track(sz), shadow = ClockGlow, family = fam)
      if (showDate) {
        Spacer(Modifier.width((12 * scale).dp))
        Box(Modifier.width(1.5.dp).height((30 * scale).dp).background(Color(0x66FFFFFF)))
        Column(Modifier.padding(start = (12 * scale).dp)) {
          T(java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(java.util.Date(now)), (14 * scale).sp, weight = FontWeight.SemiBold, shadow = ClockGlow)
          T(java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(now)), (13 * scale).sp, weight = FontWeight.Medium, color = Ink.copy(alpha = 0.8f), shadow = ClockGlow)
        }
      }
    }
    ClockStyle.SPLIT -> Column(modifier, horizontalAlignment = align) {
      val sz = 124f * scale
      val h = cal.get(if (use24h) Calendar.HOUR_OF_DAY else Calendar.HOUR).let { if (!use24h && it == 0) 12 else it }
      Row(verticalAlignment = Alignment.Bottom) {
        T(if (use24h) "%02d".format(h) else "$h", sz.sp, weight = FontWeight.Bold, spacing = track(sz), shadow = ClockGlow, family = fam)
        Spacer(Modifier.width((10 * scale).dp))
        T("%02d".format(cal.get(Calendar.MINUTE)), sz.sp, weight = if (w == FontWeight.Bold) FontWeight.Light else w, spacing = track(sz), color = Color(0xFFBFD0FF), shadow = ClockGlow, family = fam)
      }
      date(17f)
    }
    ClockStyle.ANALOG -> Column(modifier, horizontalAlignment = align) {
      val d = (176 * scale).dp
      AnalogDial(cal, d, look.weight)
      Spacer(Modifier.height((10 * scale).dp))
      date(16f)
    }
    ClockStyle.WORDS -> Column(modifier, horizontalAlignment = align) {
      val (a, b) = WordClock.words(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
      val sz = 68f * scale
      T(a, sz.sp, weight = w, spacing = track(sz * 0.6f), shadow = ClockGlow, family = fam, align = ta)
      if (b.isNotEmpty()) T(b, sz.sp, Modifier.offset(y = (-10 * scale).dp), weight = w, spacing = track(sz * 0.6f), color = Color(0xFFBFD0FF), shadow = ClockGlow, family = fam, align = ta)
      date(16f)
    }
  }
}

@Composable
private fun AnalogDial(cal: Calendar, d: androidx.compose.ui.unit.Dp, weight: ClockWeight) {
  val min = cal.get(Calendar.MINUTE); val hr = cal.get(Calendar.HOUR) + min / 60f
  val thick = when (weight) { ClockWeight.THIN -> 0.7f; ClockWeight.LIGHT -> 0.85f; ClockWeight.REGULAR -> 1f; ClockWeight.BOLD -> 1.35f }
  Canvas(Modifier.size(d)) {
    val r = size.minDimension / 2; val c = Offset(size.width / 2, size.height / 2)
    drawCircle(Color.Black, r * 1.02f, c, alpha = 0.18f)                                   // soft shade for legibility
    drawCircle(Color.White, r, c, alpha = 0.38f, style = Stroke(r * 0.018f * thick))
    for (i in 0 until 12) {
      val a = i * PI / 6; val major = i % 3 == 0
      val p = c + Offset((sin(a) * r * 0.84f).toFloat(), (-cos(a) * r * 0.84f).toFloat())
      drawCircle(Color.White, r * (if (major) 0.035f else 0.016f), p, alpha = if (major) 0.95f else 0.6f)
    }
    fun hand(turns: Float, len: Float, w: Float, col: Color) {
      val a = turns * 2 * PI
      drawLine(col, c - Offset((sin(a) * r * 0.08f).toFloat(), (-cos(a) * r * 0.08f).toFloat()),
        c + Offset((sin(a) * r * len).toFloat(), (-cos(a) * r * len).toFloat()), w, StrokeCap.Round)
    }
    hand(hr / 12f, 0.5f, r * 0.07f * thick, Color.White)
    hand(min / 60f, 0.74f, r * 0.04f * thick, Color(0xFFE8ECF8))
    drawCircle(Accent, r * 0.055f, c)
  }
}
