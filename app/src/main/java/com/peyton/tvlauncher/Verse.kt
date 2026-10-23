package com.peyton.tvlauncher

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/*
 * Verse of the day from API.Bible (NIV, non-commercial Starter plan, Peyton's own key).
 *
 * Rules this file implements (API.Bible Terms + FUMS docs, see README › Verse of the day):
 *  - The key is never in the repo or the APK. It's entered once on the TV via the LAN QR page, checked once, and stored
 *    only in app-private prefs that are excluded from backups and device transfer. It is never logged or shown again.
 *  - Text is never bundled. Today + the next 14 days are fetched in one pass and cached by date; anything older than
 *    30 days (or for a past date) is purged, and entries older than 14 days are re-fetched.
 *  - The card shows the text exactly as returned (markup stripped only), the API's reference and copyright line, and
 *    "API.Bible · api.bible". Each display is reported to FUMS with the response's fumsToken.
 */

data class VerseCred(val key: String, val bibleId: String, val abbr: String)

/** One cached day. [date] is ISO yyyy-MM-dd. */
data class Verse(val date: String, val verseId: String, val reference: String, val text: String, val copyright: String,
                 val fums: String?, val fetchedAt: Long)

enum class VerseStatus(val message: String) {
  LOADING("Getting today's verse from API.Bible…"),
  INVALID_KEY("API.Bible no longer accepts the saved key. Add a new key in Settings › Verse of the day."),
  FORBIDDEN("API.Bible refused access to the NIV for this key. Check your app on api.bible, then add the key again."),
  RATE_LIMITED("API.Bible's monthly limit for this key was reached. The verse will come back when it resets."),
  OFFLINE("Couldn't reach API.Bible. Retrying when the TV is back online."),
  SERVER("API.Bible had a problem. Retrying later."),
}

object VerseText {
  /** The longest passage (plain characters) that fits the 228dp side card without truncation at the smallest size. */
  const val MAX_CHARS = 210
  const val MAX_REF = 40

  private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "copy" to "©", "reg" to "®", "trade" to "™", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
    "ldquo" to "“", "rdquo" to "”", "hellip" to "…")

  /** Removes HTML/markup, verse-number brackets and pilcrows, decodes entities, collapses whitespace. Never rewords. */
  fun plain(s: String): String {
    var t = s.replace(Regex("<[^>]*>"), " ")
    t = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(t) { m ->
      val e = m.groupValues[1]
      when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        else -> entities[e.lowercase()] ?: m.value
      }
    }
    t = t.replace(Regex("\\[\\d+\\]"), " ").replace('¶', ' ')
    return t.replace(Regex("\\s+"), " ").trim()
  }

  /** Used when the API's copyright line can't be parsed (the NIV's current notice, short form). */
  const val NIV_SHORT = "NIV © 2011 Biblica, Inc. All rights reserved."

  /**
   * Short-form notice for the card, per API.Bible Terms Appendix A ("If space constrained, you may abbreviate to:
   * [Bible translation abbreviation] © [Year] [Organization name.] All rights reserved."). Year = the latest year in the
   * API's copyright line, holder = the text after "by" (e.g. "… 1973, 1978, 1984, 2011 by Biblica, Inc.® Used by …"
   * → "NIV © 2011 Biblica, Inc. All rights reserved."). Falls back to [NIV_SHORT] for the NIV, else just the abbreviation.
   */
  fun shortNotice(abbr: String, copyright: String): String {
    val c = copyright.replace(Regex("[®™]"), " ").replace(Regex("\\s+"), " ")
    val year = Regex("\\b(1[89]\\d\\d|2\\d\\d\\d)\\b").findAll(c).map { it.value.toInt() }.maxOrNull()
    val holder = Regex("(?:\\d{4})\\s*,?\\s*by\\s+(.+?)(?:\\s+Used by|\\s+All rights|\\s*$)", RegexOption.IGNORE_CASE).find(c)
      ?.groupValues?.get(1)?.trim()?.trimEnd(',', ';')?.takeIf { it.length in 3..40 }
    return when {
      year != null && holder != null -> "$abbr © $year ${holder.trimEnd('.')}. All rights reserved."
      abbr == "NIV" -> NIV_SHORT
      else -> abbr
    }
  }

  fun fits(text: String, reference: String) = text.isNotEmpty() && text.length <= MAX_CHARS && reference.length <= MAX_REF
}

sealed class KeyCheck {
  data class Valid(val bibleId: String, val abbr: String, val name: String) : KeyCheck()
  /** [retry] = not the key's fault (network, limit, server): worth trying again later. */
  data class Invalid(val reason: String, val retry: Boolean = false) : KeyCheck()
}

sealed class VerseFetch {
  data class Ok(val reference: String, val text: String, val copyright: String, val fums: String?) : VerseFetch()
  /** 404 (not in this Bible) — try the next candidate. */
  object Missing : VerseFetch()
  data class Failed(val status: VerseStatus) : VerseFetch()
}

object VerseApi {
  /** Test seams: point at a local fake server. */
  @Volatile internal var apiBase = "https://rest.api.bible"
  @Volatile internal var fumsBase = "https://fums.api.bible"

  /** Plausible API.Bible key: letters/digits/-/_ only (keys are 32 hex chars today). Anything else is refused before any request. */
  fun looksLikeKey(k: String) = k.length in 16..128 && k.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

  private class Resp(val code: Int, val body: String)

  /** GET with the key header. Never logs; the key goes only to [apiBase]. code -1 = network error. */
  private fun get(url: String, key: String): Resp = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000; c.readTimeout = 15_000
    c.setRequestProperty("api-key", key); c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "TvLauncher/1.0")
    try {
      val code = c.responseCode
      Resp(code, if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else "")
    } finally { c.disconnect() }
  } catch (_: IOException) { Resp(-1, "") }

  /**
   * Finds the NIV among the Bibles the key can use. API.Bible lists it as "NIV11" / "New International Version 2011", so
   * match an abbreviation starting with NIV or a name containing "New International Version", but never the NIrV
   * (Reader's Version) or the Anglicised NIVUK. Preference: NIV, NIV11, other. Shown on the card as "NIV".
   */
  fun findNiv(json: String): KeyCheck.Valid? = try {
    val arr = JSONObject(json).getJSONArray("data")
    val all = (0 until arr.length()).map { arr.getJSONObject(it) }
    fun abbrs(o: JSONObject) = listOf(o.optString("abbreviation"), o.optString("abbreviationLocal")).map { it.trim().uppercase() }
    fun isNiv(o: JSONObject): Boolean {
      val name = o.optString("name").lowercase(); val ab = abbrs(o)
      if ("reader" in name || "anglici" in name || ab.any { it.startsWith("NIRV") || it.startsWith("NIVUK") }) return false
      return ab.any { it.startsWith("NIV") } || "new international version" in name
    }
    fun rank(o: JSONObject) = abbrs(o).let { if ("NIV" in it) 0 else if ("NIV11" in it) 1 else 2 }
    all.filter(::isNiv).minByOrNull(::rank)?.let { KeyCheck.Valid(it.getString("id"), "NIV", it.optString("name").ifBlank { "New International Version" }) }
  } catch (_: Exception) { null }

  /** One-time check when the key is entered: the key works and its app includes the NIV (Bible id discovered, not hardcoded). */
  fun validate(key: String): KeyCheck {
    if (!looksLikeKey(key)) return KeyCheck.Invalid("That doesn't look like an API.Bible key. Copy the whole key from your API.Bible dashboard.")
    val r = get("$apiBase/v1/bibles?language=eng", key)
    return when (r.code) {
      200 -> findNiv(r.body) ?: KeyCheck.Invalid("This key works, but your API.Bible app doesn't include the NIV yet. Add it on api.bible, then send the key again.")
      401 -> KeyCheck.Invalid("API.Bible didn't accept this key. Copy it again from your API.Bible dashboard.")
      403 -> KeyCheck.Invalid("API.Bible refused this key. Check that your app is active on api.bible.")
      429 -> KeyCheck.Invalid("API.Bible's limit for this key was reached. Try again later.", retry = true)
      -1 -> KeyCheck.Invalid("The TV couldn't reach API.Bible. Check the network and try again.", retry = true)
      else -> KeyCheck.Invalid("API.Bible had a problem (HTTP ${r.code}). Try again later.", retry = r.code >= 500)
    }
  }

  fun verseUrl(bibleId: String, verseId: String) = "$apiBase/v1/bibles/$bibleId/verses/$verseId?content-type=text" +
    "&include-notes=false&include-titles=false&include-chapter-numbers=false&include-verse-numbers=false&include-verse-spans=false&fums-version=3"

  fun parseVerse(json: String): VerseFetch.Ok? = try {
    val o = JSONObject(json); val d = o.getJSONObject("data")
    VerseFetch.Ok(VerseText.plain(d.getString("reference")), VerseText.plain(d.getString("content")), VerseText.plain(d.optString("copyright")),
      o.optJSONObject("meta")?.optString("fumsToken")?.takeIf { it.isNotBlank() })
  } catch (_: Exception) { null }

  fun fetch(c: VerseCred, verseId: String): VerseFetch {
    val r = get(verseUrl(c.bibleId, verseId), c.key)
    return when (r.code) {
      200 -> parseVerse(r.body)?.takeIf { it.text.isNotEmpty() } ?: VerseFetch.Failed(VerseStatus.SERVER)
      404, 400 -> VerseFetch.Missing
      401 -> VerseFetch.Failed(VerseStatus.INVALID_KEY)
      403 -> VerseFetch.Failed(VerseStatus.FORBIDDEN)
      429 -> VerseFetch.Failed(VerseStatus.RATE_LIMITED)
      -1 -> VerseFetch.Failed(VerseStatus.OFFLINE)
      else -> VerseFetch.Failed(VerseStatus.SERVER)
    }
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
  fun fumsUrl(token: String, deviceId: String, sessionId: String) = "$fumsBase/f3?t=${enc(token)}&dId=${enc(deviceId)}&sId=${enc(sessionId)}"

  /** Manual FUMS report (anonymous random device/session ids). Blocking, best effort. */
  fun reportFums(token: String, deviceId: String, sessionId: String): Boolean = try {
    val c = URL(fumsUrl(token, deviceId, sessionId)).openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "TvLauncher/1.0")
    try { c.responseCode in 200..299 } finally { c.disconnect() }
  } catch (_: IOException) { false }
}

/**
 * App-private storage. Two prefs files, both excluded from backup and device transfer (res/xml backup_rules + data_extraction_rules):
 * "apibible_secret" (key + discovered Bible id) and "verse_cache" (fetched days + anonymous FUMS device id).
 */
class VerseStore(ctx: Context) {
  private val secret = ctx.applicationContext.getSharedPreferences(SECRET, Context.MODE_PRIVATE)
  private val cache = ctx.applicationContext.getSharedPreferences(CACHE, Context.MODE_PRIVATE)

  var cred: VerseCred?
    get() {
      val k = secret.getString("key", null) ?: return null
      val id = secret.getString("bibleId", null) ?: return null
      return VerseCred(k, id, secret.getString("abbr", "NIV")!!)
    }
    set(v) { secret.edit().apply { clear(); if (v != null) putString("key", v.key).putString("bibleId", v.bibleId).putString("abbr", v.abbr) }.commit() }

  val hasKey get() = secret.contains("key")
  val abbr get() = secret.getString("abbr", "NIV")!!

  val deviceId: String get() = cache.getString("dId", null) ?: java.util.UUID.randomUUID().toString().also { cache.edit().putString("dId", it).apply() }

  /** The cached verse for [d], unless it's older than [MAX_AGE_MS] (never shown past the 30-day limit). */
  fun get(d: LocalDate, now: Long = System.currentTimeMillis()): Verse? =
    cache.getString("v:$d", null)?.let { decode(it) }?.takeIf { now - it.fetchedAt <= MAX_AGE_MS }

  fun put(v: Verse) { cache.edit().putString("v:${v.date}", encode(v)).apply() }

  fun dates(): List<LocalDate> = cache.all.keys.filter { it.startsWith("v:") }.mapNotNull { runCatching { LocalDate.parse(it.removePrefix("v:")) }.getOrNull() }.sorted()

  /** Drops past days and anything fetched more than 30 days ago. Returns how many were removed. */
  fun purge(today: LocalDate, now: Long = System.currentTimeMillis()): Int {
    val e = cache.edit(); var n = 0
    cache.all.forEach { (k, v) ->
      if (!k.startsWith("v:")) return@forEach
      val d = runCatching { LocalDate.parse(k.removePrefix("v:")) }.getOrNull()
      val old = (v as? String)?.let { decode(it) }?.let { now - it.fetchedAt > MAX_AGE_MS } ?: true
      if (d == null || d.isBefore(today) || old) { e.remove(k); n++ }
    }
    e.commit(); return n
  }

  fun clearVerses() { cache.edit().apply { cache.all.keys.filter { it.startsWith("v:") }.forEach { remove(it) } }.commit() }
  /** "Remove key": forget the key and every cached verse. */
  fun clearAll() { secret.edit().clear().commit(); cache.edit().clear().commit() }

  private fun encode(v: Verse) = JSONObject().put("date", v.date).put("id", v.verseId).put("ref", v.reference).put("text", v.text)
    .put("copy", v.copyright).put("fums", v.fums ?: "").put("at", v.fetchedAt).toString()
  private fun decode(s: String): Verse? = try {
    val o = JSONObject(s)
    Verse(o.getString("date"), o.getString("id"), o.getString("ref"), o.getString("text"), o.optString("copy"), o.optString("fums").ifBlank { null }, o.getLong("at"))
  } catch (_: Exception) { null }

  companion object {
    const val SECRET = "apibible_secret"
    const val CACHE = "verse_cache"
    const val DAY_MS = 24 * 60 * 60_000L
    /** API.Bible: cached content must be refreshed at least every 30 days. */
    const val MAX_AGE_MS = 30 * DAY_MS
    /** We refresh after 14 days (API.Bible's recommendation). */
    const val REFRESH_MS = 14 * DAY_MS
    /** Today + 14 days per pass. */
    const val AHEAD_DAYS = 14
  }
}

object VerseSync {
  /** Days in today..today+14 with no entry or one older than 14 days, today first. */
  fun needed(store: VerseStore, today: LocalDate, now: Long): List<LocalDate> =
    (0..VerseStore.AHEAD_DAYS).map { today.plusDays(it.toLong()) }.filter { d ->
      val v = store.get(d, now); v == null || now - v.fetchedAt > VerseStore.REFRESH_MS
    }

  /**
   * One pass: purge, then fetch every needed day (day's reference, then spares if it's too long or missing; if nothing
   * fits, the shortest is kept and the card shrinks its type). Stops at the first key/limit/network error.
   * Returns null on success or the error. On 401/403 every cached verse is deleted (access ended).
   */
  fun run(store: VerseStore, cred: VerseCred, today: LocalDate, now: Long = System.currentTimeMillis()): VerseStatus? {
    store.purge(today, now)
    for (d in needed(store, today, now)) {
      var best: VerseFetch.Ok? = null; var bestId = ""
      for (id in VerseRefs.candidates(d)) {
        when (val r = VerseApi.fetch(cred, id)) {
          is VerseFetch.Ok -> {
            if (VerseText.fits(r.text, r.reference)) { best = r; bestId = id; break }
            if (best == null || r.text.length < best.text.length) { best = r; bestId = id }
          }
          VerseFetch.Missing -> {}
          is VerseFetch.Failed -> {
            if (r.status == VerseStatus.INVALID_KEY || r.status == VerseStatus.FORBIDDEN) store.clearVerses()
            return r.status
          }
        }
      }
      best?.let { store.put(Verse(d.toString(), bestId, it.reference, it.text, it.copyright, it.fums, now)) }
    }
    return null
  }
}

/**
 * Private builds only: a key compiled in as BuildConfig.API_BIBLE_SEED is checked once and moved into the same private
 * storage the QR path uses. Public builds have an empty seed, and the stored key survives updates (same package + signer),
 * so a later public update keeps working. Removing the key in Settings turns the seed off for good; a seed API.Bible
 * rejects isn't retried until a new build.
 */
object VerseSeed {
  enum class Result { NONE, SAVED, REJECTED, RETRY_LATER }
  private const val OFF = "verseSeedOff"
  private const val REJECTED_VC = "verseSeedRejectedVc"

  fun migrate(store: VerseStore, launcher: android.content.SharedPreferences, seed: String, versionCode: Long,
              validate: (String) -> KeyCheck = VerseApi::validate): Result {
    if (seed.isEmpty() || store.hasKey || launcher.getBoolean(OFF, false) || launcher.getLong(REJECTED_VC, -1L) == versionCode) return Result.NONE
    return when (val r = validate(seed)) {
      is KeyCheck.Valid -> { store.cred = VerseCred(seed, r.bibleId, r.abbr); Result.SAVED }
      is KeyCheck.Invalid -> if (r.retry) Result.RETRY_LATER else { launcher.edit().putLong(REJECTED_VC, versionCode).apply(); Result.REJECTED }
    }
  }

  /** Pref for Settings › Verse of the day › Show copyright on card (default ON). */
  const val NOTICE = "verseNotice"

  /** Private build: seed "Show copyright on card" OFF once, only if the user has never set it. Returns true if it changed. */
  fun applyNoticeDefault(launcher: android.content.SharedPreferences, offByDefault: Boolean): Boolean {
    if (!offByDefault || launcher.contains(NOTICE)) return false
    launcher.edit().putBoolean(NOTICE, false).commit(); return true
  }

  /** "Remove API.Bible key" in Settings: a seeded build must not quietly put it back. */
  fun turnOff(launcher: android.content.SharedPreferences) { launcher.edit().putBoolean(OFF, true).apply() }
}
