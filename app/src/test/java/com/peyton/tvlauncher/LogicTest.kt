package com.peyton.tvlauncher

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
/** Pure logic: dock edit key sequences, face-aware crop, Ken Burns containment, ambient plan, word clock, countdown, stats. */
class LogicTest {
  private val dock = listOf("yt", "netflix", "disney", "hulu", "prime")

  private fun ed(vararg keys: EditKey, start: Int = 0, saved: MutableList<List<String>>? = null) =
    DockEditor(dock, start) { saved?.add(it) }.apply { keys.forEach { key(it) } }

  @Test fun pickMoveDrop() {
    val saved = mutableListOf<List<String>>()
    val e = ed(EditKey.OK, EditKey.RIGHT, EditKey.RIGHT, EditKey.OK, saved = saved)   // pick yt, move 2 right, drop
    assertEquals(listOf("netflix", "disney", "yt", "hulu", "prime"), e.items)
    assertFalse(e.picked); assertEquals(2, e.focus)
    assertEquals(2, saved.size)                       // saved live on every move
  }
  @Test fun pickedCannotPassEnds() {
    val e = ed(EditKey.OK, EditKey.LEFT, EditKey.LEFT)
    assertEquals(dock, e.items); assertTrue(e.picked)
    val e2 = ed(EditKey.OK, *Array(9) { EditKey.RIGHT }, start = 3)
    assertEquals(listOf("yt", "netflix", "disney", "prime", "hulu"), e2.items); assertEquals(4, e2.focus)
  }
  @Test fun badgeRemoveAndUndo() {
    val e = ed(EditKey.RIGHT, EditKey.DOWN)
    assertTrue(e.onBadge)
    e.key(EditKey.OK)
    assertEquals(listOf("yt", "disney", "hulu", "prime"), e.items); assertEquals("netflix" to 1, e.undo); assertFalse(e.onBadge)
    e.key(EditKey.UP)                                 // undo
    assertEquals(dock, e.items); assertEquals(1, e.focus); assertNull(e.undo)
  }
  @Test fun menuRemovesInstantly() {
    val e = ed(EditKey.RIGHT, EditKey.RIGHT, EditKey.MENU)
    assertEquals(listOf("yt", "netflix", "hulu", "prime"), e.items); assertEquals("hulu", e.focusedPkg)
  }
  @Test fun upLeavesBadgeWithoutUndo() {
    val e = ed(EditKey.DOWN, EditKey.UP)
    assertFalse(e.onBadge); assertEquals(dock, e.items)
  }
  @Test fun addTileOpensChecklistAndToggles() {
    val e = ed(*Array(5) { EditKey.RIGHT })
    assertEquals(e.addIndex, e.focus)
    e.key(EditKey.OK); assertTrue(e.checklist)
    e.key(EditKey.LEFT); assertEquals(e.addIndex, e.focus)   // keys ignored while the checklist is open
    e.toggle("plex"); e.toggle("netflix")
    assertEquals(listOf("yt", "disney", "hulu", "prime", "plex"), e.items)
    e.closeChecklist(); assertFalse(e.checklist); assertEquals(e.addIndex, e.focus)
  }
  @Test fun doneAndBack() {
    val e = ed(*Array(6) { EditKey.RIGHT }); assertEquals(e.doneIndex, e.focus)
    e.key(EditKey.OK); assertTrue(e.done)
    val b = ed(EditKey.OK, EditKey.BACK); assertFalse(b.picked); assertFalse(b.done)    // BACK drops first
    b.key(EditKey.BACK); assertTrue(b.done)
  }
  @Test fun removeLastKeepsFocusValid() {
    val e = DockEditor(listOf("a")); e.key(EditKey.MENU)
    assertTrue(e.items.isEmpty()); assertEquals(e.addIndex, e.focus)
    e.key(EditKey.UP); assertEquals(listOf("a"), e.items)
  }
  @Test fun hintFollowsState() {
    assertTrue(ed().hint().startsWith("OK  Pick up"))
    assertTrue(ed(EditKey.OK).hint().startsWith("◀ ▶  Move"))
    assertTrue(ed(EditKey.DOWN).hint().startsWith("OK  Remove"))
  }

  // ---- crop
  @Test fun landscapeCropKeepsEdgeFace() {
    // 4:3 photo, face near the top: centre crop (12.5% off top) would clip it.
    val face = NRect(0.40f, 0.04f, 0.52f, 0.22f)
    val (c, fits) = Crop.fit(4000, 3000, listOf(face), 0.5f, 0.5f, 16f / 9f)
    assertTrue(fits); assertEquals(0f, c.t, 0.01f); assertEquals(0.75f, c.h, 0.01f)
    assertTrue(c.contains(face.inflate(Crop.FACE_PAD).clamp01()))
  }
  @Test fun wideCropFollowsFaces() {
    val faces = listOf(NRect(0.80f, 0.3f, 0.86f, 0.4f), NRect(0.90f, 0.32f, 0.95f, 0.42f))
    val (c, fits) = Crop.fit(3000, 1000, faces, 0.5f, 0.5f, 16f / 9f)   // 3:1 panorama
    assertTrue(fits); assertTrue(c.r >= 0.95f); assertTrue(c.contains(NRect.union(faces.map { it.inflate(Crop.FACE_PAD).clamp01() })!!))
  }
  @Test fun portraitCropPutsFacesHigh() {
    val face = NRect(0.37f, 0.20f, 0.72f, 0.47f)       // p832
    val (c, fits) = Crop.fit(1080, 1620, listOf(face), 0.5f, 0.5f, 0.89f)
    assertTrue(fits); assertTrue(c.contains(face))
    val cy = (face.cy - c.t) / c.h; assertTrue("face centre at $cy", cy in 0.3f..0.55f)
  }
  @Test fun spreadFacesReportNoFit() {
    val faces = listOf(NRect(0.02f, 0.4f, 0.1f, 0.5f), NRect(0.9f, 0.4f, 0.98f, 0.5f))
    assertFalse(Crop.fit(1600, 1000, faces, 0.5f, 0.5f, 1f).second)
  }
  @Test fun noFacesUsesSubject() {
    val (c, _) = Crop.fit(3000, 1000, emptyList(), 0.2f, 0.5f, 16f / 9f)
    assertEquals(0f, c.l, 0.02f)
  }
  @Test fun kenBurnsNeverLosesFaces() {
    val faces = listOf(NRect(0.05f, 0.08f, 0.30f, 0.45f), NRect(0.62f, 0.55f, 0.95f, 0.95f), NRect(0.4f, 0.4f, 0.6f, 0.6f))
    for (f in faces) for (zoomIn in listOf(true, false)) for (i in 0..50) {
      val (s, tx, ty) = Crop.kenBurns(i / 50f, f, zoomIn)
      val w = Crop.window(s, tx, ty)
      assertTrue("t=${i / 50f} $f window $w", w.contains(f)); assertTrue(w.l >= -1e-4f && w.r <= 1.0001f && w.t >= -1e-4f && w.b <= 1.0001f)
      assertTrue(s >= 1f && s <= 1.16f + 1e-4f)
    }
    val (sBig, _, _) = Crop.kenBurns(1f, NRect(0.1f, 0.1f, 0.9f, 0.9f), true)
    assertEquals(1f, sBig, 0.02f)                   // a face filling the frame isn't zoomed
  }
  @Test fun ambientPlanPairsPortraitsSkipsFlagged() {
    fun m(w: Int, h: Int, flag: String? = null, fits: Boolean = true) = PhotoMeta(w, h, emptyList(), .5f, .5f, NRect.FULL, fits, 100f, flag, 0f, 1f, .5f)
    val p = (1..7).map { Photo(file = "/p$it.jpg") }
    val plan = AmbientPlan.build(listOf(p[0] to m(1080, 1620), p[1] to m(1920, 1080), p[2] to m(1080, 1620), p[3] to m(1080, 1620, "Blurry"),
      p[4] to m(1080, 1620), p[5] to m(1600, 1200, fits = false), p[6] to null))
    assertEquals(listOf(SlideSpec.Full(p[1]), SlideSpec.Pair2(p[0], p[2]), SlideSpec.Contain(p[5]), SlideSpec.Full(p[6]), SlideSpec.Contain(p[4])), plan)
    // all flagged → still shows something
    assertEquals(1, AmbientPlan.build(listOf(p[0] to m(1920, 1080, "Low-res"))).size)
  }
  @Test fun metaJsonRoundTrip() {
    val m = PhotoMeta(2560, 1440, listOf(NRect(0.1f, 0.2f, 0.3f, 0.4f)), 0.4f, 0.5f, NRect(0.1f, 0f, 0.9f, 1f), true, 88.5f, "Blurry", 0.05f, 0.9f, 0.4f)
    assertEquals(m, PhotoMeta.parse(m.json()))
  }

  // ---- image stats
  @Test fun laplacianSeparatesSharpFromBlurry() {
    val w = 128; val h = 72
    val sharp = FloatArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 0.9f else 0.1f }
    val blurry = FloatArray(w * h) { i -> 0.5f + 0.1f * kotlin.math.sin((i % w) / 20f) }
    val vs = ImageStats.laplacianVariance(sharp, w, h); val vb = ImageStats.laplacianVariance(blurry, w, h)
    assertTrue("sharp=$vs", vs > Analyzer.BLURRY * 10); assertTrue("blurry=$vb", vb < Analyzer.BLURRY)
  }
  @Test fun saliencyFindsBusyRegion() {
    val w = 96; val h = 54
    val g = FloatArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 60..80 && y in 15..35 && (x + y) % 2 == 0) 1f else 0.3f }
    val (sx, _) = ImageStats.saliency(g, w, h); assertTrue("sx=$sx", sx > 0.6f)
  }
  @Test fun enhanceOnlyWhenNeeded() {
    fun m(lo: Float, hi: Float, mean: Float) = PhotoMeta(1, 1, emptyList(), .5f, .5f, NRect.FULL, true, 100f, null, lo, hi, mean)
    assertNull(Enhance.matrix(m(0.02f, 0.97f, 0.46f)))
    val flat = Enhance.matrix(m(0.25f, 0.65f, 0.40f))!!
    assertTrue(flat[0] > 1.05f && flat[0] <= 1.3f); assertTrue(flat[4] < 0)
  }

  // ---- clock & countdown
  @Test fun wordClock() {
    assertEquals("Nine thirty-two", WordClock.phrase(21, 32))
    assertEquals("Nine thirty-two", WordClock.phrase(9, 32))
    assertEquals("Twelve oh five", WordClock.phrase(0, 5))
    assertEquals("Midnight", WordClock.phrase(0, 0)); assertEquals("Noon", WordClock.phrase(12, 0))
    assertEquals("Seven o'clock", WordClock.phrase(19, 0)); assertEquals("One fifteen", WordClock.phrase(13, 15))
    assertEquals("Eleven fifty-nine", WordClock.phrase(23, 59))
  }
  @Test fun countdown() {
    val today = LocalDate.of(2026, 10, 18)
    assertEquals(12L, Countdown("Mom's birthday", 10, 30).daysLeft(today))
    assertEquals(0L, Countdown("Today", 10, 18).daysLeft(today))
    assertEquals(LocalDate.of(2027, 3, 2), Countdown("x", 3, 2).next(today))           // yearly rolls over
    assertEquals(LocalDate.of(2027, 2, 28), Countdown("x", 2, 31).next(today))         // clamps to month length
    assertNull(Countdown("x", 0, 0).daysLeft(today))
    assertEquals(LocalDate.of(2028, 1, 1), Countdown("x", 1, 1, 2028, yearly = false).next(today))
  }
}
