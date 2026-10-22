package com.peyton.tvlauncher

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Home screen arrangements. All share the same dock, cards and clock components. */
enum class HomeLayout(val title: String, val blurb: String) {
  GLASS("Glass dock", "Centered clock, cards on top, app dock at the bottom."),
  CINEMATIC("Cinematic", "Full-bleed photo, small clock and cards top-right, big app row bottom-left."),
  SIDE_RAIL("Side rail", "Clock and cards in a glass column, every app in a grid on the right."),
  MINIMAL("Minimal", "Just the photo and a small clock. The dock appears when you press a button."),
}

enum class CardSlot(val title: String) { OFF("Off"), LEFT("Top-left"), RIGHT("Top-right") }

enum class HomeCard(val title: String, val blurb: String) {
  WEATHER("Weather", "Now and the next hours"),
  WEATHER3("Weather · 3-day", "Today and the next 3 days"),
  INPUTS("Inputs", "HDMI sources and Live TV"),
  DATE("Date & day", "Big date, weekday, week number"),
  RECENT("Recent apps", "What you opened last"),
  PHOTO("Photo of the day", "One of your photos, changes daily"),
  TOGGLES("Quick buttons", "Ambient, Settings, Inputs"),
  COUNTDOWN("Countdown", "Days until a date you set"),
  NOTE("Note", "A short message you type"),
  VERSE("Verse of the day", "NIV via API.Bible (needs your key)"),
}

enum class ClockStyle(val title: String) {
  CENTERED("Centered · large thin"), STACKED("Stacked · left-aligned"), CORNER("Minimal · small"),
  SPLIT("Split · bold hours"), ANALOG("Analog · minimal"), WORDS("Words · \u201cNine thirty-two\u201d"),
}

enum class ClockFont(val title: String) { INTER("Inter"), MANROPE("Manrope"), OUTFIT("Outfit"), PLAYFAIR("Playfair Display"), SPACE("Space Grotesk"), DMSERIF("DM Serif Display") }

enum class ClockWeight(val title: String, val fw: FontWeight) { THIN("Thin", FontWeight.ExtraLight), LIGHT("Light", FontWeight.Light), REGULAR("Regular", FontWeight.Normal), BOLD("Bold", FontWeight.Bold) }

data class ClockLook(val style: ClockStyle = ClockStyle.CENTERED, val font: ClockFont = ClockFont.INTER, val weight: ClockWeight = ClockWeight.THIN, val inAmbient: Boolean = false)

data class Countdown(val title: String = "", val month: Int = 0, val day: Int = 0, val year: Int = 0, val yearly: Boolean = true) {
  val isSet get() = month in 1..12 && day in 1..31
  /** Next occurrence on/after [today] (yearly events roll over), or null if unset/invalid. */
  fun next(today: LocalDate): LocalDate? {
    if (!isSet) return null
    fun at(y: Int) = try { LocalDate.of(y, month, minOf(day, java.time.YearMonth.of(y, month).lengthOfMonth())) } catch (_: Exception) { null }
    if (!yearly) return at(if (year > 0) year else today.year)
    val d = at(today.year) ?: return null
    return if (d.isBefore(today)) at(today.year + 1) else d
  }
  fun daysLeft(today: LocalDate): Long? = next(today)?.let { ChronoUnit.DAYS.between(today, it) }
}

object Look {
  fun layout(p: Prefs) = runCatching { HomeLayout.valueOf(p.sp.getString("layout", "GLASS")!!) }.getOrDefault(HomeLayout.GLASS)

  /** Stored as "WEATHER:LEFT,INPUTS:RIGHT"; first run mirrors v4 (weather left if it was on, inputs right). */
  fun cards(p: Prefs): Map<HomeCard, CardSlot> {
    val raw = p.sp.getString("cards", null)
    if (raw == null) return linkedMapOf(HomeCard.WEATHER to (if (p.showWeather) CardSlot.LEFT else CardSlot.OFF), HomeCard.INPUTS to CardSlot.RIGHT)
    val m = LinkedHashMap<HomeCard, CardSlot>()
    raw.split(',').forEach { e -> val (k, v) = (e.split(':') + listOf("", "")).take(2); runCatching { m[HomeCard.valueOf(k)] = CardSlot.valueOf(v) } }
    return m
  }
  fun saveCards(p: Prefs, m: Map<HomeCard, CardSlot>) = p.sp.edit().putString("cards", m.entries.joinToString(",") { "${it.key.name}:${it.value.name}" }).apply()

  fun clock(p: Prefs) = ClockLook(
    runCatching { ClockStyle.valueOf(p.sp.getString("clockStyle", "CENTERED")!!) }.getOrDefault(ClockStyle.CENTERED),
    runCatching { ClockFont.valueOf(p.sp.getString("clockFont", "INTER")!!) }.getOrDefault(ClockFont.INTER),
    runCatching { ClockWeight.valueOf(p.sp.getString("clockWeight", "THIN")!!) }.getOrDefault(ClockWeight.THIN),
    p.sp.getBoolean("clockAmbient", false))
  fun saveClock(p: Prefs, c: ClockLook) = p.sp.edit().putString("clockStyle", c.style.name).putString("clockFont", c.font.name)
    .putString("clockWeight", c.weight.name).putBoolean("clockAmbient", c.inAmbient).apply()

  fun countdown(p: Prefs) = Countdown(p.sp.getString("cdTitle", "") ?: "", p.sp.getInt("cdMonth", 0), p.sp.getInt("cdDay", 0), p.sp.getInt("cdYear", 0), p.sp.getBoolean("cdYearly", true))
  fun saveCountdown(p: Prefs, c: Countdown) = p.sp.edit().putString("cdTitle", c.title).putInt("cdMonth", c.month).putInt("cdDay", c.day).putInt("cdYear", c.year).putBoolean("cdYearly", c.yearly).apply()

  /** Cards in a slot, in a stable order (enum order). */
  fun inSlot(m: Map<HomeCard, CardSlot>, slot: CardSlot) = HomeCard.values().filter { m[it] == slot }
}

/** Bundled OFL fonts (subset to Latin, static weights; see assets/licenses/OFL-fonts.txt). */
object ClockFonts {
  private val cache = HashMap<ClockFont, FontFamily>()
  fun family(f: ClockFont): FontFamily = cache.getOrPut(f) {
    fun four(t: Int, l: Int, r: Int, b: Int) = FontFamily(Font(t, FontWeight.ExtraLight), Font(l, FontWeight.Light), Font(r, FontWeight.Normal), Font(b, FontWeight.Bold))
    when (f) {
      ClockFont.INTER -> Inter
      ClockFont.MANROPE -> four(R.font.manrope_thin, R.font.manrope_light, R.font.manrope_regular, R.font.manrope_bold)
      ClockFont.OUTFIT -> four(R.font.outfit_thin, R.font.outfit_light, R.font.outfit_regular, R.font.outfit_bold)
      ClockFont.PLAYFAIR -> four(R.font.playfair_regular, R.font.playfair_regular, R.font.playfair_regular, R.font.playfair_bold)
      ClockFont.SPACE -> four(R.font.spacegrotesk_light, R.font.spacegrotesk_light, R.font.spacegrotesk_regular, R.font.spacegrotesk_bold)
      ClockFont.DMSERIF -> FontFamily(Font(R.font.dmserif_regular, FontWeight.Normal))
    }
  }
}

/** "Nine thirty-two" style time, split into (hour words, minute words). */
object WordClock {
  private val ones = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
  private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty")
  fun number(n: Int): String = if (n < 20) ones[n] else tens[n / 10] + if (n % 10 != 0) "-" + ones[n % 10] else ""
  fun words(h24: Int, m: Int): Pair<String, String> {
    if (m == 0 && h24 == 0) return "Midnight" to ""
    if (m == 0 && h24 == 12) return "Noon" to ""
    val h = ((h24 + 11) % 12) + 1
    val hw = number(h).replaceFirstChar { it.uppercase() }
    return hw to when { m == 0 -> "o'clock"; m < 10 -> "oh ${number(m)}"; else -> number(m) }
  }
  fun phrase(h24: Int, m: Int) = words(h24, m).let { (a, b) -> if (b.isEmpty()) a else "$a $b" }
}
