package com.peyton.tvlauncher

import android.view.KeyEvent
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Weather location, entered on the TV as a US ZIP code (nothing location-specific is compiled into the APK). */
data class WeatherPlace(val zip: String, val city: String, val state: String, val lat: Double, val lon: Double) {
  /** "New York City, NY" on cards; "New York City, NY 10001" in Settings. */
  val label get() = if (state.isNotEmpty()) "$city, $state" else city
  val fullLabel get() = "$label $zip"
}

sealed class ZipResult {
  data class Found(val place: WeatherPlace) : ZipResult()
  data class Failed(val reason: String) : ZipResult()
}

object ZipLookup {
  /** Test seam: points the lookup at a local fake. Zippopotam.us is free and needs no key. */
  @Volatile internal var apiBase = "https://api.zippopotam.us"

  fun isValid(zip: String) = zip.length == 5 && zip.all { it in '0'..'9' }

  /** Parses https://api.zippopotam.us/us/<zip> (first place wins). Null if the reply has no usable place. */
  fun parse(zip: String, json: String): WeatherPlace? = try {
    val p = JSONObject(json).getJSONArray("places").getJSONObject(0)
    val city = p.getString("place name").trim()
    val lat = p.getString("latitude").toDouble(); val lon = p.getString("longitude").toDouble()
    if (city.isEmpty() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
    else WeatherPlace(zip, city, p.optString("state abbreviation").trim(), lat, lon)
  } catch (_: Exception) { null }

  /** Blocking; never throws. */
  fun lookup(zip: String): ZipResult {
    if (!isValid(zip)) return ZipResult.Failed("Enter all 5 digits of the ZIP code.")
    return try {
      val c = URL("$apiBase/us/$zip").openConnection() as HttpURLConnection
      c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "TvLauncher/1.0")
      try {
        when (val code = c.responseCode) {
          200 -> parse(zip, c.inputStream.bufferedReader().use { it.readText() })?.let { ZipResult.Found(it) }
            ?: ZipResult.Failed("Couldn't read the location for $zip. Try again later.")
          404 -> ZipResult.Failed("$zip isn't a known US ZIP code.")
          else -> ZipResult.Failed("The ZIP lookup service had a problem (HTTP $code). Try again later.")
        }
      } finally { c.disconnect() }
    } catch (_: IOException) {
      ZipResult.Failed("No internet connection. Check the network and try again.")
    }
  }
}

/**
 * 5-digit ZIP picker driven by a TV remote: ▲/▼ change the selected digit (wrapping), ◀/▶ move, number keys type
 * and advance, OK saves. Pure state so key handling is unit-tested; the screen just draws it.
 */
class ZipEntry(initial: String?) {
  val digits = IntArray(5) { i -> initial?.getOrNull(i)?.digitToIntOrNull() ?: 0 }
  var pos = 0; private set
  val zip get() = digits.joinToString("")

  enum class Result { NONE, CHANGED, SUBMIT }

  fun key(code: Int): Result = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> { digits[pos] = (digits[pos] + 1) % 10; Result.CHANGED }
    KeyEvent.KEYCODE_DPAD_DOWN -> { digits[pos] = (digits[pos] + 9) % 10; Result.CHANGED }
    KeyEvent.KEYCODE_DPAD_LEFT -> if (pos > 0) { pos--; Result.CHANGED } else Result.NONE
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (pos < 4) { pos++; Result.CHANGED } else Result.NONE
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
      digits[pos] = if (code >= KeyEvent.KEYCODE_NUMPAD_0) code - KeyEvent.KEYCODE_NUMPAD_0 else code - KeyEvent.KEYCODE_0
      if (pos < 4) pos++; Result.CHANGED
    }
    KeyEvent.KEYCODE_DEL -> { digits[pos] = 0; if (pos > 0) pos--; Result.CHANGED }
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> Result.SUBMIT
    else -> Result.NONE
  }
}
