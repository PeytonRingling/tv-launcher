package com.peyton.tvlauncher

import android.os.Looper
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** ZIP validation, Zippopotam parsing (fixtures, no live network), persistence, error states and the picker's key handling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherLocationTest {
  // Shape of https://api.zippopotam.us/us/10001 (a public example ZIP, not anyone's home).
  private val fixture = """{"post code": "10001", "country": "United States", "country abbreviation": "US",
    "places": [{"place name": "New York City", "longitude": "-73.9967", "state": "New York", "state abbreviation": "NY", "latitude": "40.7484"}]}"""

  @After fun reset() { ZipLookup.apiBase = "https://api.zippopotam.us" }

  @Test fun validation() {
    assertTrue(ZipLookup.isValid("10001")); assertTrue(ZipLookup.isValid("00501"))
    listOf("", "1000", "100011", "1000a", " 1000", "10-01").forEach { assertFalse(it, ZipLookup.isValid(it)) }
    assertTrue((ZipLookup.lookup("123") as ZipResult.Failed).reason.contains("5 digits"))
  }

  @Test fun parsesFixture() {
    val p = ZipLookup.parse("10001", fixture)!!
    assertEquals(WeatherPlace("10001", "New York City", "NY", 40.7484, -73.9967), p)
    assertEquals("New York City, NY", p.label); assertEquals("New York City, NY 10001", p.fullLabel)
  }

  @Test fun rejectsBadReplies() {
    assertNull(ZipLookup.parse("10001", "{}"))
    assertNull(ZipLookup.parse("10001", """{"places": []}"""))
    assertNull(ZipLookup.parse("10001", "<html>"))
    assertNull(ZipLookup.parse("10001", """{"places":[{"place name":"X","latitude":"95","longitude":"0"}]}"""))
  }

  @Test fun lookupAgainstFakeServer() { FakeGitHub().use { s ->
    ZipLookup.apiBase = s.base
    s.json = fixture
    assertEquals("New York City", (ZipLookup.lookup("10001") as ZipResult.Found).place.city)
    assertEquals("/us/10001", s.paths.last())
    s.status = 404; s.json = "{}"
    assertTrue((ZipLookup.lookup("99999") as ZipResult.Failed).reason.contains("isn't a known US ZIP"))
    s.status = 500
    assertTrue((ZipLookup.lookup("10001") as ZipResult.Failed).reason.contains("HTTP 500"))
    s.status = 200; s.json = "garbage"
    assertTrue((ZipLookup.lookup("10001") as ZipResult.Failed).reason.contains("Couldn't read"))
  } }

  @Test fun noNetwork() {
    ZipLookup.apiBase = "http://127.0.0.1:1"
    assertTrue((ZipLookup.lookup("10001") as ZipResult.Failed).reason.contains("No internet"))
  }

  @Test fun persistenceAndPlaceChange() {
    val ctx = RuntimeEnvironment.getApplication(); ctx.getSharedPreferences("launcher", 0).edit().clear().commit()
    val prefs = Prefs(ctx)
    assertNull(prefs.weatherPlace)
    val st = LauncherState(prefs)
    assertNull(st.place); assertNull(st.weather)                   // fresh install / v4.2 upgrade: prompt, no location
    prefs.weatherJson = "{}"
    val p = WeatherPlace("10001", "New York City", "NY", 40.7484, -73.9967)
    st.changePlace(p)
    assertEquals(p, Prefs(ctx).weatherPlace)                       // survives restart
    assertNull(Prefs(ctx).weatherJson)                              // old forecast dropped
    assertEquals(p, LauncherState(Prefs(ctx)).place)
    assertTrue(WeatherApi.url(p).contains("latitude=40.7484&longitude=-73.9967") && WeatherApi.url(p).contains("timezone=auto"))
  }

  @Test fun upgradeFromV42IgnoresOldForecastWithoutPlace() {
    val ctx = RuntimeEnvironment.getApplication(); ctx.getSharedPreferences("launcher", 0).edit().clear().putString("weatherJson",
      """{"current":{"time":"2026-01-01T10:00","temperature_2m":40,"weather_code":0,"is_day":1},"daily":{"temperature_2m_max":[50],"temperature_2m_min":[30]}}""").commit()
    assertNull(LauncherState(Prefs(ctx)).weather)   // a v4.2 cache has no place attached; show the ZIP prompt instead
  }

  @Test fun pickerKeys() {
    val e = ZipEntry(null)
    assertEquals("00000", e.zip)
    assertEquals(ZipEntry.Result.NONE, e.key(KeyEvent.KEYCODE_DPAD_LEFT))     // already leftmost
    e.key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("90000", e.zip)          // wraps 0 → 9
    e.key(KeyEvent.KEYCODE_DPAD_UP); e.key(KeyEvent.KEYCODE_DPAD_UP); assertEquals("10000", e.zip)
    e.key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(1, e.pos)
    e.key(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(0, e.pos)
    listOf(KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_5).forEach { e.key(it) }
    assertEquals("94105", e.zip); assertEquals(4, e.pos)                       // stays on the last digit
    assertEquals(ZipEntry.Result.NONE, e.key(KeyEvent.KEYCODE_DPAD_RIGHT))
    e.key(KeyEvent.KEYCODE_DEL); assertEquals("94100", e.zip); assertEquals(3, e.pos)
    assertEquals(ZipEntry.Result.SUBMIT, e.key(KeyEvent.KEYCODE_DPAD_CENTER))
    assertEquals(ZipEntry.Result.SUBMIT, e.key(KeyEvent.KEYCODE_ENTER))
    assertEquals(ZipEntry.Result.NONE, e.key(KeyEvent.KEYCODE_BACK))
    assertEquals("12345", ZipEntry("12345").zip)
  }

  @Test fun activityKeepsOldPlaceOnErrorAndSavesOnSuccess() { FakeGitHub().use { s ->
    ZipLookup.apiBase = s.base
    val ctx = RuntimeEnvironment.getApplication(); ctx.getSharedPreferences("launcher", 0).edit().clear().commit()
    val old = WeatherPlace("10001", "New York City", "NY", 40.7484, -73.9967); Prefs(ctx).weatherPlace = old
    val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = ctrl.get()
    fun idle(cond: () -> Boolean) { val end = System.currentTimeMillis() + 15_000; while (!cond()) { shadowOf(Looper.getMainLooper()).idle(); check(System.currentTimeMillis() < end); Thread.sleep(20) } }
    a.st.screen = Screen.WEATHER_ZIP
    s.status = 404
    a.setZip("99999"); idle { a.st.zipStatus != "…" }
    assertTrue(a.st.zipStatus!!.contains("isn't a known")); assertEquals(old, a.st.place); assertEquals(old, Prefs(ctx).weatherPlace)
    assertEquals(Screen.WEATHER_ZIP, a.st.screen)
    s.status = 200; s.json = """{"places":[{"place name":"Beverly Hills","state abbreviation":"CA","latitude":"34.0901","longitude":"-118.4065"}]}"""
    a.setZip("90210"); idle { a.st.zipStatus == null }
    assertEquals("Beverly Hills, CA 90210", a.st.place!!.fullLabel); assertEquals(a.st.place, Prefs(ctx).weatherPlace)
    assertEquals(Screen.SETTINGS, a.st.screen)
    ctrl.pause().stop().destroy()
  } }
}
