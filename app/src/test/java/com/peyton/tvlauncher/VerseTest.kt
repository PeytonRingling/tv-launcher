package com.peyton.tvlauncher

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Verse of the day: parsing, markup stripping, NIV discovery, key check, cache window, sync, FUMS, errors, key server. Fixtures + local fakes only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerseTest {
  private val ctx get() = RuntimeEnvironment.getApplication()
  private val key = "0123456789abcdef0123456789abcdef"   // fake, never a real key
  private val cred = VerseCred(key, "fixture-niv-01", "NIV")
  private val today = LocalDate.of(2026, 10, 21)
  private val day = VerseStore.DAY_MS
  private val now = 1_800_000_000_000L

  @Before fun clean() {
    listOf("launcher", VerseStore.SECRET, VerseStore.CACHE).forEach { ctx.getSharedPreferences(it, 0).edit().clear().commit() }
  }
  @After fun reset() { VerseApi.apiBase = "https://rest.api.bible"; VerseApi.fumsBase = "https://fums.api.bible" }

  // ---------------------------------------------------------------- text

  @Test fun stripsMarkupAndEntities() {
    assertEquals("Yahweh is my shepherd; I shall lack nothing.", VerseText.plain(
      "<p class=\"q1\"><span data-number=\"1\" class=\"v\">[1]</span>Yahweh is my shepherd;</p>\n<p class=\"q2\">I shall lack nothing.&nbsp;&#182;</p>"))
    assertEquals("a & b < c \"d\" e’s — f ©", VerseText.plain("a &amp; b &lt; c &quot;d&quot; e&#x2019;s &mdash; f &copy;"))
    assertEquals("Keep &unknown; as is", VerseText.plain("Keep &unknown; as is"))
    assertEquals("one two", VerseText.plain("  one\n\t  two ¶ "))
    assertEquals("", VerseText.plain("<br/> "))
  }

  @Test fun shortNotice() {
    // Shape of the live NIV copyright field (a notice, not Scripture text).
    val live = "The Holy Bible, New International Version® NIV® Copyright © 1973, 1978, 1984, 2011 by Biblica, Inc.® Used by Permission of Biblica, Inc.® All rights reserved worldwide."
    assertEquals("NIV © 2011 Biblica, Inc. All rights reserved.", VerseText.shortNotice("NIV", live))
    assertEquals("NIV © 2011 Biblica, Inc. All rights reserved.", VerseText.shortNotice("NIV", "Copyright © 2011 by Biblica, Inc. All rights reserved."))
    assertEquals("XYZ © 2026 Example Org. All rights reserved.", VerseText.shortNotice("XYZ", "Text © 2001, 2026 by Example Org"))
    assertEquals(VerseText.NIV_SHORT, VerseText.shortNotice("NIV", ""))                      // unparseable → fixed short form
    assertEquals(VerseText.NIV_SHORT, VerseText.shortNotice("NIV", "Fixture text: World English Bible (public domain)."))
    assertEquals("WEB", VerseText.shortNotice("WEB", "Public domain."))
  }

  @Test fun fitsCard() {
    assertTrue(VerseText.fits("Jesus wept.", "John 11:35"))
    assertTrue(VerseText.fits("x".repeat(VerseText.MAX_CHARS), "John 3:16"))
    assertFalse(VerseText.fits("x".repeat(VerseText.MAX_CHARS + 1), "John 3:16"))
    assertFalse(VerseText.fits("", "John 3:16"))
    assertFalse(VerseText.fits("short", "r".repeat(VerseText.MAX_REF + 1)))
    val long = VerseApi.parseVerse(fixture("verse_long.json"))!!
    assertFalse(VerseText.fits(long.text, long.reference))
  }

  @Test fun parsesVerseFixtures() {
    val v = VerseApi.parseVerse(fixture("verse_short.json"))!!
    assertEquals("John 3:16", v.reference)
    assertTrue(v.text.startsWith("For God so loved the world")); assertTrue(v.text.endsWith("eternal life."))
    assertEquals("fixture+fums/token=1", v.fums)
    assertTrue(v.copyright.startsWith("Fixture text"))
    val m = VerseApi.parseVerse(fixture("verse_markup.json"))!!
    assertEquals("Yahweh is my shepherd; I shall lack nothing.", m.text)
    assertEquals("Fixture © 2026 Example Org. All rights reserved.", m.copyright)
    assertNull(VerseApi.parseVerse("{}")); assertNull(VerseApi.parseVerse("<html>"))
    assertNull(VerseApi.parseVerse("""{"data":{"reference":"x","content":"y"}}""")!!.fums)   // no meta → no token
  }

  // ---------------------------------------------------------------- key check

  @Test fun findsNivByAbbreviationOnly() {
    val v = VerseApi.findNiv(fixture("bibles.json"))!!
    assertEquals("fixture-niv-01", v.bibleId); assertEquals("NIV", v.abbr); assertEquals("New International Version", v.name)
    assertNull(VerseApi.findNiv(fixture("bibles_no_niv.json")))   // NIrV / KJV aren't the NIV
    // Live listing shape (no text): API.Bible calls it NIV11 / "New International Version 2011"; skip NIrV and NIVUK.
    val live = VerseApi.findNiv(fixture("bibles_niv11.json"))!!
    assertEquals("78a9f6124f344018-01", live.bibleId); assertEquals("NIV", live.abbr); assertEquals("New International Version 2011", live.name)
    assertEquals("78a9f6124f344018-01", VerseApi.findNiv(fixture("bibles_niv11.json").replace("\"abbreviationLocal\":\"NIV\"", "\"abbreviationLocal\":\"NIV11\""))!!.bibleId)
    assertNull(VerseApi.findNiv("""{"data":[{"id":"u","abbreviation":"NIVUK","name":"New International Version (Anglicised)"}]}"""))
    assertNull(VerseApi.findNiv("""{"data":[]}""")); assertNull(VerseApi.findNiv("nope"))
  }

  @Test fun validateAgainstFake() { FakeHttp().use { s ->
    VerseApi.apiBase = s.base
    s.route = { FakeHttp.Res(200, fixture("bibles.json")) }
    val ok = VerseApi.validate(key) as KeyCheck.Valid
    assertEquals("fixture-niv-01", ok.bibleId)
    assertEquals("/v1/bibles?language=eng", s.requests.last().path)
    assertEquals(key, s.requests.last().headers["api-key"])
    s.route = { FakeHttp.Res(200, fixture("bibles_no_niv.json")) }
    assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).reason.contains("doesn't include the NIV"))
    s.route = { FakeHttp.Res(401, "{}") }; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).reason.contains("didn't accept"))
    s.route = { FakeHttp.Res(403, "{}") }; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).reason.contains("refused"))
    s.route = { FakeHttp.Res(429, "{}") }; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).reason.contains("limit"))
    s.route = { FakeHttp.Res(500, "{}") }; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).reason.contains("HTTP 500"))
    val n = s.requests.size
    listOf("", "short", "has space in it 0123456789", "ключ0123456789abcdef").forEach { bad ->
      assertTrue(bad, (VerseApi.validate(bad) as KeyCheck.Invalid).reason.contains("doesn't look like"))
    }
    assertEquals("malformed keys are never sent", n, s.requests.size)
  } }

  @Test fun validateOffline() {
    VerseApi.apiBase = "http://127.0.0.1:1"
    assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).reason.contains("couldn't reach"))
  }

  // ---------------------------------------------------------------- references

  @Test fun dayToReference() {
    assertEquals(366, VerseRefs.DAYS.size); assertEquals(366, VerseRefs.DAYS.toSet().size)
    val re = Regex("[1-3A-Z][A-Z0-9]{2}\\.\\d+\\.\\d+")
    (VerseRefs.DAYS + VerseRefs.SPARES).forEach { assertTrue(it, re.matches(it)) }
    assertTrue(VerseRefs.SPARES.none { it in VerseRefs.DAYS })
    assertEquals(0, VerseRefs.index(LocalDate.of(2027, 1, 1)))
    assertEquals(58, VerseRefs.index(LocalDate.of(2027, 2, 28)))
    assertEquals(59, VerseRefs.index(LocalDate.of(2028, 2, 29)))
    assertEquals(60, VerseRefs.index(LocalDate.of(2027, 3, 1))); assertEquals(60, VerseRefs.index(LocalDate.of(2028, 3, 1)))
    assertEquals(365, VerseRefs.index(LocalDate.of(2027, 12, 31))); assertEquals(365, VerseRefs.index(LocalDate.of(2028, 12, 31)))
    assertEquals("LUK.2.11", VerseRefs.forDate(LocalDate.of(2026, 12, 25))); assertEquals("LUK.2.11", VerseRefs.forDate(LocalDate.of(2028, 12, 25)))
    // every date of a leap and a common year maps to a distinct reference
    for (y in listOf(2027, 2028)) { val l = (0 until LocalDate.of(y, 12, 31).dayOfYear).map { VerseRefs.forDate(LocalDate.of(y, 1, 1).plusDays(it.toLong())) }; assertEquals(l.size, l.toSet().size) }
    val c = VerseRefs.candidates(today); assertEquals(3, c.size); assertEquals(VerseRefs.forDate(today), c[0]); assertTrue(c.drop(1).all { it in VerseRefs.SPARES })
  }

  // ---------------------------------------------------------------- cache

  private fun v(d: LocalDate, at: Long, text: String = "Text $d") = Verse(d.toString(), "JHN.3.16", "John 3:16", text, "©", "tok-$d", at)

  @Test fun cacheWindowAndPurge() {
    val st = VerseStore(ctx)
    st.put(v(today, now - 29 * day)); st.put(v(today.plusDays(1), now - 31 * day)); st.put(v(today.minusDays(1), now - day)); st.put(v(today.plusDays(2), now))
    assertNotNull(st.get(today, now))                                  // 29 days old: still shown
    assertNull(st.get(today.plusDays(1), now))                         // over 30 days: never shown
    assertEquals(2, st.purge(today, now))                              // yesterday + the 31-day-old entry
    assertEquals(listOf(today, today.plusDays(2)), st.dates())
    // needed: today (29 days > 14-day refresh) + every missing day up to today+14; fresh today+2 is skipped
    val need = VerseSync.needed(st, today, now)
    assertEquals(14, need.size); assertEquals(today, need.first()); assertEquals(today.plusDays(14), need.last()); assertFalse(today.plusDays(2) in need)
  }

  @Test fun storageIsPrivateAndSeparate() {
    val st = VerseStore(ctx); st.cred = cred; st.put(v(today, now))
    assertEquals(cred, VerseStore(ctx).cred)
    val launcher = ctx.getSharedPreferences("launcher", 0).all.values.joinToString()
    assertFalse("key never in the main prefs", launcher.contains(key))
    assertFalse("key never in the cache file", ctx.getSharedPreferences(VerseStore.CACHE, 0).all.values.joinToString().contains(key))
    st.clearAll(); assertNull(VerseStore(ctx).cred); assertNull(VerseStore(ctx).get(today, now))
  }

  @Test fun backupRulesExcludeKeyAndCache() {
    val files = listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml").map { java.io.File(it).readText() }
    files.forEach { x -> listOf("apibible_secret.xml", "verse_cache.xml").forEach { assertTrue(it, x.contains("<exclude domain=\"sharedpref\" path=\"$it\" />")) } }
    assertEquals(2, Regex("<device-transfer>|<cloud-backup>").findAll(files[1]).count())
    val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
    assertTrue(manifest.contains("@xml/backup_rules") && manifest.contains("@xml/data_extraction_rules"))
  }

  // ---------------------------------------------------------------- sync against a fake API.Bible

  private fun verseRoute(long: Set<String> = emptySet(), missing: Set<String> = emptySet()): (FakeHttp.Req) -> FakeHttp.Res = { r ->
    val id = r.path.substringAfter("/verses/").substringBefore('?')
    when {
      id in missing -> FakeHttp.Res(404, "{}")
      id in long -> FakeHttp.Res(200, fixture("verse_long.json"))
      id in VerseRefs.SPARES -> FakeHttp.Res(200, fixture("verse_spare.json").replace("JHN.11.35", id))
      else -> FakeHttp.Res(200, fixture("verse_short.json").replace("fixture+fums/token=1", "tok-$id"))
    }
  }

  @Test fun syncFetchesFifteenDaysOnceAndCaches() { FakeHttp(verseRoute()).use { s ->
    VerseApi.apiBase = s.base; val st = VerseStore(ctx)
    assertNull(VerseSync.run(st, cred, today, now))
    assertEquals(15, s.requests.size)
    val p = s.requests.first().path
    assertTrue(p.startsWith("/v1/bibles/fixture-niv-01/verses/${VerseRefs.forDate(today)}?"))
    listOf("content-type=text", "include-verse-numbers=false", "include-titles=false", "include-notes=false", "fums-version=3").forEach { assertTrue(it, p.contains(it)) }
    assertTrue(s.requests.all { it.headers["api-key"] == key })
    assertEquals((0..14).map { today.plusDays(it.toLong()) }, st.dates())
    assertEquals("tok-${VerseRefs.forDate(today)}", st.get(today, now)!!.fums)
    assertNull(VerseSync.run(st, cred, today, now + 60_000)); assertEquals("cached: no new calls", 15, s.requests.size)
    // next day: one new day at the end, yesterday purged
    assertNull(VerseSync.run(st, cred, today.plusDays(1), now + day)); assertEquals(16, s.requests.size)
    assertEquals(today.plusDays(1), st.dates().first()); assertEquals(today.plusDays(15), st.dates().last())
    // 15 days later everything is past the 14-day refresh: refetched
    s.requests.clear(); assertNull(VerseSync.run(st, cred, today.plusDays(1), now + 16 * day)); assertEquals(15, s.requests.size)
  } }

  @Test fun tooLongOrMissingFallsBackToSpare() { FakeHttp().use { s ->
    VerseApi.apiBase = s.base; val st = VerseStore(ctx)
    val d1 = VerseRefs.forDate(today); val d2 = VerseRefs.forDate(today.plusDays(1))
    s.route = verseRoute(long = setOf(d1), missing = setOf(d2))
    assertNull(VerseSync.run(st, cred, today, now))
    val a = st.get(today, now)!!; assertEquals(VerseRefs.candidates(today)[1], a.verseId); assertEquals("Jesus wept.", a.text)
    assertTrue(VerseText.fits(a.text, a.reference))
    assertEquals(VerseRefs.candidates(today.plusDays(1))[1], st.get(today.plusDays(1), now)!!.verseId)
  } }

  @Test fun nothingFitsKeepsShortestForClamping() { FakeHttp().use { s ->
    VerseApi.apiBase = s.base; val st = VerseStore(ctx)
    val c = VerseRefs.candidates(today)
    s.route = { r -> val id = r.path.substringAfter("/verses/").substringBefore('?')
      if (id == c[1]) FakeHttp.Res(200, fixture("verse_long.json").replace("India to Ethiopia, ", "")) else FakeHttp.Res(200, fixture("verse_long.json")) }
    assertNull(VerseSync.run(st, cred, today, now))
    val v = st.get(today, now)!!
    assertEquals(c[1], v.verseId); assertFalse(VerseText.fits(v.text, v.reference))   // card shrinks to 12sp, never cut
  } }

  @Test fun errorStates() { FakeHttp().use { s ->
    VerseApi.apiBase = s.base; val st = VerseStore(ctx)
    st.put(v(today, now))
    s.route = { FakeHttp.Res(429, "{}") }
    assertEquals(VerseStatus.RATE_LIMITED, VerseSync.run(st, cred, today, now)); assertEquals(1, s.requests.size)   // stops at once
    assertNotNull("limit keeps the cache", st.get(today, now))
    s.route = { FakeHttp.Res(500, "{}") }; assertEquals(VerseStatus.SERVER, VerseSync.run(st, cred, today, now)); assertNotNull(st.get(today, now))
    s.route = { FakeHttp.Res(200, "not json") }; assertEquals(VerseStatus.SERVER, VerseSync.run(st, cred, today, now))
    s.route = { FakeHttp.Res(403, "{}") }; assertEquals(VerseStatus.FORBIDDEN, VerseSync.run(st, cred, today, now)); assertNull("access ended: cache deleted", st.get(today, now))
    st.put(v(today, now))
    s.route = { FakeHttp.Res(401, "{}") }; assertEquals(VerseStatus.INVALID_KEY, VerseSync.run(st, cred, today, now)); assertTrue(st.dates().isEmpty())
    VerseApi.apiBase = "http://127.0.0.1:1"; st.put(v(today, now))
    assertEquals(VerseStatus.OFFLINE, VerseSync.run(st, cred, today, now)); assertNotNull("offline keeps the cache", st.get(today, now))
  } }

  // ---------------------------------------------------------------- FUMS

  @Test fun fumsReport() { FakeHttp { FakeHttp.Res(200, "") }.use { s ->
    VerseApi.fumsBase = s.base
    assertTrue(VerseApi.reportFums("fixture+fums/token=1", "dev-1", "ses-1"))
    assertEquals("GET", s.requests.last().method)
    assertEquals("/f3?t=fixture%2Bfums%2Ftoken%3D1&dId=dev-1&sId=ses-1", s.requests.last().path)
    assertFalse("no key ever goes to FUMS", s.requests.last().headers.containsKey("api-key"))
    s.route = { FakeHttp.Res(500, "") }; assertFalse(VerseApi.reportFums("t", "d", "s"))
    VerseApi.fumsBase = "http://127.0.0.1:1"; assertFalse(VerseApi.reportFums("t", "d", "s"))
  } }

  // ---------------------------------------------------------------- LAN key page

  @Test fun keyServerPostsKeyToTv() {
    val got = java.util.concurrent.CopyOnWriteArrayList<String>()
    val srv = PhotoServer.startKey(ctx, object : KeyHooks { override fun onKey(key: String) = (key == this@VerseTest.key).also { got += key } to if (key == this@VerseTest.key) "Saved." else "Nope." })!!
    try {
      val base = "http://127.0.0.1:${srv.listeningPort}"
      fun call(path: String, body: String? = null): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "text/plain"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode; return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
      }
      assertEquals(403, call("/000000").first)
      val page = call("/${srv.pin}"); assertEquals(200, page.first); assertTrue(page.second.contains("API.Bible key")); assertTrue(page.second.contains("type=password"))
      val (code, msg) = call("/${srv.pin}/key", "  $key \n")
      assertEquals(200, code); assertEquals("{\"ok\":true,\"message\":\"Saved.\"}", msg); assertEquals(listOf(key), got)   // trimmed
      assertFalse("never echoed", msg.contains(key))
      assertTrue(call("/${srv.pin}/key", "x".repeat(600)).second.contains("wrong length"))
      assertEquals(404, call("/${srv.pin}/upload", "x").first)   // key mode only accepts keys
    } finally { srv.stop() }
  }

  // ---------------------------------------------------------------- end to end through MainActivity

  @Test fun activityKeyFlowSyncFumsAndRemove() { FakeHttp().use { s ->
    VerseApi.apiBase = s.base; VerseApi.fumsBase = s.base
    val routeVerse = verseRoute()
    s.route = { r -> when {
      r.path.startsWith("/v1/bibles?") -> FakeHttp.Res(200, fixture("bibles.json"))
      r.path.startsWith("/f3") -> FakeHttp.Res(200, "")
      else -> routeVerse(r)
    } }
    ShadowLog.clear()
    val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = ctrl.get()
    fun idle(cond: () -> Boolean) { val end = System.currentTimeMillis() + 20_000; while (!cond()) { shadowOf(Looper.getMainLooper()).idle(); check(System.currentTimeMillis() < end); Thread.sleep(20) } }
    assertFalse(a.st.verseKey); assertNull(a.st.verse)
    // invalid key: nothing stored
    s.route.let { ok -> s.route = { r -> if (r.path.startsWith("/v1/bibles?")) FakeHttp.Res(401, "{}") else ok(r) }
      assertFalse(a.keyHooks.onKey(key).first); s.route = ok }
    assertNull(VerseStore(ctx).cred); assertEquals(KeyPhase.ERROR, a.st.keyUi!!.phase)
    // valid key: stored, NIV id discovered, first pass runs
    val (ok, msg) = a.keyHooks.onKey(key)
    assertTrue(ok); assertTrue(msg.contains("New International Version (NIV)"))
    idle { a.st.verse != null }
    assertEquals(VerseCred(key, "fixture-niv-01", "NIV"), VerseStore(ctx).cred)
    assertTrue(a.st.verseKey); assertNull(a.st.verseStatus)
    assertEquals(VerseRefs.forDate(LocalDate.now()), a.st.verse!!.verseId)
    // FUMS: once per token per session
    a.reportFums(a.st.verse!!.fums!!); a.reportFums(a.st.verse!!.fums!!)
    idle { s.requests.any { it.path.startsWith("/f3") } }
    Thread.sleep(200)
    assertEquals(1, s.requests.count { it.path.startsWith("/f3") })
    // never logged
    assertTrue(ShadowLog.getLogs().none { it.msg?.contains(key) == true })
    // remove
    a.removeVerseKey()
    assertNull(VerseStore(ctx).cred); assertTrue(VerseStore(ctx).dates().isEmpty()); assertFalse(a.st.verseKey); assertNull(a.st.verse)
    ctrl.pause().stop().destroy()
  } }

  @Test fun cachedVerseIsInStateBeforeFirstFrame() {
    VerseStore(ctx).put(v(LocalDate.now(), System.currentTimeMillis(), "Cached text"))
    assertEquals("Cached text", LauncherState(Prefs(ctx)).verse!!.text)   // no pop-in: read synchronously with the prefs
  }

  // ---------------------------------------------------------------- private-build seed

  private val launcherSp get() = ctx.getSharedPreferences("launcher", 0)
  private val valid: (String) -> KeyCheck = { KeyCheck.Valid("fixture-niv-01", "NIV", "New International Version") }

  @Test fun publicBuildsHaveNoSeed() = assertEquals("", BuildConfig.API_BIBLE_SEED)

  /** Private v4.3 seeds the key; a later public (unseeded) update finds it in the same private storage. */
  @Test fun seededInstallThenUnseededUpgradeKeepsKey() {
    assertEquals(VerseSeed.Result.SAVED, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8, valid))
    assertEquals(cred, VerseStore(ctx).cred)
    // upgrade: new process, same app data, empty seed, higher versionCode
    var called = false
    assertEquals(VerseSeed.Result.NONE, VerseSeed.migrate(VerseStore(ctx), launcherSp, "", 9) { called = true; valid(it) })
    assertFalse(called)
    assertEquals(cred, VerseStore(ctx).cred); assertTrue(LauncherState(Prefs(ctx)).verseKey)
  }

  @Test fun seedNeverOverwritesOrResurrects() {
    VerseStore(ctx).cred = VerseCred("qr-key-0123456789abcdef", "fixture-niv-01", "NIV")       // entered via QR
    assertEquals(VerseSeed.Result.NONE, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8, valid))
    assertEquals("qr-key-0123456789abcdef", VerseStore(ctx).cred!!.key)
    VerseStore(ctx).clearAll(); VerseSeed.turnOff(launcherSp)                                    // "Remove API.Bible key"
    assertEquals(VerseSeed.Result.NONE, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8, valid))
    assertNull(VerseStore(ctx).cred)
  }

  @Test fun seedRejectedOrOffline() {
    var calls = 0
    val bad: (String) -> KeyCheck = { calls++; KeyCheck.Invalid("didn't accept") }
    assertEquals(VerseSeed.Result.REJECTED, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8, bad))
    assertEquals(VerseSeed.Result.NONE, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8, bad)); assertEquals("not retried in the same build", 1, calls)
    assertEquals(VerseSeed.Result.REJECTED, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 9, bad)); assertEquals(2, calls)
    clean()
    assertEquals(VerseSeed.Result.RETRY_LATER, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8) { KeyCheck.Invalid("offline", retry = true) })
    assertEquals(VerseSeed.Result.SAVED, VerseSeed.migrate(VerseStore(ctx), launcherSp, key, 8, valid))
    assertFalse("seed never stored in the main prefs", launcherSp.all.values.joinToString().contains(key))
  }

  @Test fun validateMarksRetryableErrors() { FakeHttp().use { s ->
    VerseApi.apiBase = s.base
    s.route = { FakeHttp.Res(401, "{}") }; assertFalse((VerseApi.validate(key) as KeyCheck.Invalid).retry)
    s.route = { FakeHttp.Res(200, fixture("bibles_no_niv.json")) }; assertFalse((VerseApi.validate(key) as KeyCheck.Invalid).retry)
    s.route = { FakeHttp.Res(429, "{}") }; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).retry)
    s.route = { FakeHttp.Res(503, "{}") }; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).retry)
    VerseApi.apiBase = "http://127.0.0.1:1"; assertTrue((VerseApi.validate(key) as KeyCheck.Invalid).retry)
  } }

  // ---------------------------------------------------------------- "Show copyright on card"

  @Test fun noticeSettingDefaultsOnAndPersists() {
    assertFalse(BuildConfig.VERSE_NOTICE_OFF_BY_DEFAULT)                       // public/debug builds
    assertTrue(LauncherState(Prefs(ctx)).verseNotice)
    assertFalse(VerseSeed.applyNoticeDefault(launcherSp, false)); assertTrue(LauncherState(Prefs(ctx)).verseNotice)
    LauncherState(Prefs(ctx)).changeVerseNotice(false)
    assertFalse(LauncherState(Prefs(ctx)).verseNotice)                           // survives restart
  }

  @Test fun privateBuildSeedsNoticeOffOnceOnly() {
    assertTrue(VerseSeed.applyNoticeDefault(launcherSp, true)); assertFalse(LauncherState(Prefs(ctx)).verseNotice)
    LauncherState(Prefs(ctx)).changeVerseNotice(true)                            // user turns it back on
    assertFalse(VerseSeed.applyNoticeDefault(launcherSp, true)); assertTrue(LauncherState(Prefs(ctx)).verseNotice)
  }
}
