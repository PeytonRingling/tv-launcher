package com.peyton.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Normalized (0..1) rectangle, plain JVM so the crop logic is unit-testable. */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
  val w get() = r - l; val h get() = b - t; val cx get() = (l + r) / 2; val cy get() = (t + b) / 2
  fun contains(o: NRect, eps: Float = 1e-4f) = o.l >= l - eps && o.t >= t - eps && o.r <= r + eps && o.b <= b + eps
  fun inflate(f: Float) = NRect(l - w * f, t - h * f, r + w * f, b + h * f)
  fun clamp01() = NRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
  /** This rect (in image coords) expressed inside [frame] (also image coords), normalized to the frame. */
  fun within(frame: NRect) = NRect((l - frame.l) / frame.w, (t - frame.t) / frame.h, (r - frame.l) / frame.w, (b - frame.t) / frame.h)
  companion object { val FULL = NRect(0f, 0f, 1f, 1f)
    fun union(rs: List<NRect>) = if (rs.isEmpty()) null else NRect(rs.minOf { it.l }, rs.minOf { it.t }, rs.maxOf { it.r }, rs.maxOf { it.b }) }
}

/**
 * Per-photo analysis, computed once at ingest and stored as JSON next to the photo.
 * faces/subject/crop are normalized to the image. lo/hi/mean are luminance stats (0..1) for auto-enhance.
 */
data class PhotoMeta(
  val w: Int, val h: Int, val faces: List<NRect>, val subjectX: Float, val subjectY: Float,
  val crop: NRect, val cropFits: Boolean, val sharpness: Float, val flag: String?, val lo: Float, val hi: Float, val mean: Float,
  val version: Int = VERSION,
) {
  val portrait get() = h > w * 1.08f
  val focus: NRect get() = NRect.union(faces) ?: NRect(subjectX - 0.12f, subjectY - 0.12f, subjectX + 0.12f, subjectY + 0.12f).clamp01()
  fun json(): String = JSONObject().apply {
    put("v", version); put("w", w); put("h", h); put("sx", subjectX.toDouble()); put("sy", subjectY.toDouble())
    put("faces", JSONArray().apply { faces.forEach { f -> put(JSONArray(listOf(f.l, f.t, f.r, f.b).map { it.toDouble() })) } })
    put("crop", JSONArray(listOf(crop.l, crop.t, crop.r, crop.b).map { it.toDouble() })); put("fits", cropFits)
    put("sharp", sharpness.toDouble()); flag?.let { put("flag", it) }; put("lo", lo.toDouble()); put("hi", hi.toDouble()); put("mean", mean.toDouble())
  }.toString()
  companion object {
    const val VERSION = 1
    fun parse(s: String): PhotoMeta? = try {
      val o = JSONObject(s)
      fun r(a: JSONArray) = NRect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
      val fa = o.getJSONArray("faces")
      PhotoMeta(o.getInt("w"), o.getInt("h"), (0 until fa.length()).map { r(fa.getJSONArray(it)) }, o.getDouble("sx").toFloat(), o.getDouble("sy").toFloat(),
        r(o.getJSONArray("crop")), o.optBoolean("fits", true), o.getDouble("sharp").toFloat(), o.optString("flag").ifEmpty { null },
        o.getDouble("lo").toFloat(), o.getDouble("hi").toFloat(), o.getDouble("mean").toFloat(), o.optInt("v", 0))
    } catch (_: Exception) { null }
  }
}

/** Face-aware cropping and Ken Burns planning. Pure math (no Android types). */
object Crop {
  /** Margin added around detected faces (hair, chin) before fitting. */
  const val FACE_PAD = 0.18f

  /**
   * Best crop of a [w]x[h] image to [aspect] (w/h), normalized. Keeps every face (padded) inside when possible and
   * places faces slightly above centre; without faces it centres on the [subject] point.
   * Returns (crop, fitsAllFaces).
   */
  fun fit(w: Int, h: Int, faces: List<NRect>, sx: Float, sy: Float, aspect: Float): Pair<NRect, Boolean> {
    val ia = w / h.toFloat()
    val padded = faces.map { it.inflate(FACE_PAD).clamp01() }
    val u = NRect.union(padded)
    return if (ia >= aspect) {           // too wide: choose x
      val cw = aspect / ia               // crop width, normalized
      val want = (u?.cx ?: sx) - cw / 2
      var lo = 0f; var hi = 1f - cw
      var fits = true
      if (u != null) { if (u.w <= cw) { lo = max(lo, u.r - cw); hi = min(hi, u.l) } else fits = false }
      val x = if (lo <= hi) want.coerceIn(lo, hi) else want.coerceIn(0f, 1f - cw)
      NRect(x, 0f, x + cw, 1f) to fits
    } else {                             // too tall: choose y, faces ~42% from the top
      val ch = ia / aspect
      val want = (u?.cy ?: sy) - ch * 0.42f
      var lo = 0f; var hi = 1f - ch
      var fits = true
      if (u != null) { if (u.h <= ch) { lo = max(lo, u.b - ch); hi = min(hi, u.t) } else fits = false }
      val y = if (lo <= hi) want.coerceIn(lo, hi) else want.coerceIn(0f, 1f - ch)
      NRect(0f, y, 1f, y + ch) to fits
    }
  }

  /**
   * Ken Burns pose at progress [t] for a frame showing [focus] (normalized to the frame).
   * Returns (scale, tx, ty) where tx/ty are in frame-size units for a graphicsLayer with a centre transform origin.
   * The visible window always contains the focus box: zoom is capped so the faces fit, and the window is clamped
   * inside the frame, so faces are never pushed out.
   */
  fun kenBurns(t: Float, focus: NRect?, zoomIn: Boolean, maxZoom: Float = 1.16f, frameAspect: Float = 16f / 9f): Triple<Float, Float, Float> {
    val f = focus?.let { it.inflate(0.12f).clamp01() }
    // Largest zoom whose window still contains the (padded) focus box.
    val cap = if (f == null) maxZoom else min(maxZoom, min(1f / f.w.coerceAtLeast(0.01f), 1f / f.h.coerceAtLeast(0.01f)))
    val sMax = max(1f, cap)
    val e = ease(t)
    val s = if (zoomIn) 1f + (sMax - 1f) * e else sMax - (sMax - 1f) * e
    val cx0 = f?.cx ?: 0.5f; val cy0 = f?.cy ?: 0.42f
    // Pan towards the focus as we zoom; window half-size is 0.5/s.
    val k = (s - 1f) / (sMax - 1f).coerceAtLeast(1e-4f)
    var cx = 0.5f + (cx0 - 0.5f) * k; var cy = 0.5f + (cy0 - 0.5f) * k
    val half = 0.5f / s
    cx = cx.coerceIn(half, 1f - half); cy = cy.coerceIn(half, 1f - half)
    if (f != null) {   // guarantee containment
      cx = cx.coerceIn(f.r - half, f.l + half).coerceIn(half, 1f - half)
      cy = cy.coerceIn(f.b - half, f.t + half).coerceIn(half, 1f - half)
    }
    return Triple(s, (0.5f - cx) * s, (0.5f - cy) * s)
  }
  /** Visible window (frame-normalized) for a pose, for tests. */
  fun window(s: Float, tx: Float, ty: Float): NRect { val cx = 0.5f - tx / s; val cy = 0.5f - ty / s; val h = 0.5f / s; return NRect(cx - h, cy - h, cx + h, cy + h) }
  private fun ease(t: Float) = (t * t * (3 - 2 * t)).coerceIn(0f, 1f)
}

/** Image statistics on a small grayscale copy. Pure (IntArray in, numbers out). */
object ImageStats {
  fun gray(px: IntArray): FloatArray = FloatArray(px.size) { i -> val c = px[i]; (0.2126f * (c shr 16 and 255) + 0.7152f * (c shr 8 and 255) + 0.0722f * (c and 255)) / 255f }

  /** Variance of the 4-neighbour Laplacian (x255²). Low = blurry. */
  fun laplacianVariance(g: FloatArray, w: Int, h: Int): Float {
    var sum = 0.0; var sq = 0.0; var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val i = y * w + x
      val l = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]) * 255f
      sum += l; sq += l * l; n++
    }
    if (n == 0) return 0f
    val m = sum / n; return (sq / n - m * m).toFloat()
  }

  /** Centre-weighted saliency point: gradient energy x colourfulness-free gaussian prior. */
  fun saliency(g: FloatArray, w: Int, h: Int): Pair<Float, Float> {
    var sx = 0.0; var sy = 0.0; var tot = 0.0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val i = y * w + x
      val e = abs(g[i + 1] - g[i - 1]) + abs(g[i + w] - g[i - w])
      val nx = x / w.toFloat() - 0.5f; val ny = y / h.toFloat() - 0.45f
      val prior = kotlin.math.exp(-(nx * nx + ny * ny) / 0.08f)
      val v = e * prior
      sx += v * x; sy += v * y; tot += v
    }
    if (tot <= 1e-6) return 0.5f to 0.45f
    // Blend with the centre so one busy corner can't drag the crop too far.
    return (0.5f * 0.35f + 0.65f * (sx / tot / w).toFloat()) to (0.45f * 0.35f + 0.65f * (sy / tot / h).toFloat())
  }

  /** (p2, p98, mean) luminance. */
  fun lumStats(g: FloatArray): Triple<Float, Float, Float> {
    val hist = IntArray(256); var sum = 0.0
    g.forEach { v -> hist[(v * 255).toInt().coerceIn(0, 255)]++; sum += v }
    fun pct(p: Float): Float { val target = g.size * p; var acc = 0; for (i in 0..255) { acc += hist[i]; if (acc >= target) return i / 255f }; return 1f }
    return Triple(pct(0.02f), pct(0.98f), (sum / g.size.coerceAtLeast(1)).toFloat())
  }
}

/** Subtle levels/exposure normalisation as a single colour matrix (applied at draw time, toggle in Settings). */
object Enhance {
  /** Returns the 4x5 matrix, or null when the photo is already well exposed. */
  fun matrix(m: PhotoMeta, strength: Float = 0.6f): FloatArray? {
    val range = (m.hi - m.lo).coerceAtLeast(0.05f)
    var a = (0.94f / range).coerceIn(1f, 1.3f)
    val gain = Math.pow((0.46 / m.mean.coerceIn(0.05f, 0.95f).toDouble()), 0.3).toFloat().coerceIn(0.92f, 1.12f)
    a = 1f + (a * gain - 1f) * strength
    val b = (0.03f - m.lo * a) * strength * 255f
    if (abs(a - 1f) < 0.02f && abs(b) < 3f) return null
    return floatArrayOf(a, 0f, 0f, 0f, b, 0f, a, 0f, 0f, b, 0f, 0f, a, 0f, b, 0f, 0f, 0f, 1f, 0f)
  }
}

/** Finds faces; platform FaceDetector by default (built into Android, adds nothing to the APK). */
fun interface FaceFinder { fun find(bmp: Bitmap): List<NRect> }

object PlatformFaces : FaceFinder {
  override fun find(bmp: Bitmap): List<NRect> = try {
    // android.media.FaceDetector wants RGB_565 with an even width; ~1000 px long edge balances speed and recall.
    val sc = min(1f, 1024f / max(bmp.width, bmp.height))
    var w = (bmp.width * sc).toInt().coerceAtLeast(2); if (w % 2 == 1) w--
    val h = (bmp.height * sc).toInt().coerceAtLeast(2)
    val small = Bitmap.createScaledBitmap(bmp, w, h, true).copy(Bitmap.Config.RGB_565, false)
    val arr = arrayOfNulls<android.media.FaceDetector.Face>(8)
    val n = android.media.FaceDetector(w, h, 8).findFaces(small, arr)
    val p = android.graphics.PointF()
    (0 until n).mapNotNull { i ->
      val f = arr[i] ?: return@mapNotNull null
      if (f.confidence() < 0.4f) return@mapNotNull null
      f.getMidPoint(p); val e = f.eyesDistance()
      NRect((p.x - e * 1.1f) / w, (p.y - e * 1.25f) / h, (p.x + e * 1.1f) / w, (p.y + e * 1.65f) / h).clamp01()
    }.also { small.recycle() }
  } catch (_: Throwable) { emptyList() }
}

object Analyzer {
  const val BLURRY = 22f            // Laplacian variance on a 512 px copy; sharp phone photos are usually 100+
  const val MIN_SHORT_EDGE = 600

  fun analyze(bmp: Bitmap, origW: Int = bmp.width, origH: Int = bmp.height, faces: FaceFinder = PlatformFaces): PhotoMeta {
    val sc = min(1f, 512f / max(bmp.width, bmp.height))
    val sw = (bmp.width * sc).toInt().coerceAtLeast(3); val sh = (bmp.height * sc).toInt().coerceAtLeast(3)
    val small = if (sc < 1f) Bitmap.createScaledBitmap(bmp, sw, sh, true) else bmp
    val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
    if (small !== bmp) small.recycle()
    val g = ImageStats.gray(px)
    val sharp = ImageStats.laplacianVariance(g, sw, sh)
    val (sx, sy) = ImageStats.saliency(g, sw, sh)
    val (lo, hi, mean) = ImageStats.lumStats(g)
    val fs = faces.find(bmp)
    val (crop, fits) = Crop.fit(origW, origH, fs, sx, sy, 16f / 9f)
    val flag = when { min(origW, origH) < MIN_SHORT_EDGE -> "Low-res"; sharp < BLURRY -> "Blurry"; else -> null }
    return PhotoMeta(origW, origH, fs, sx, sy, crop, fits, sharp, flag, lo, hi, mean)
  }
}

/** JSON sidecars in filesDir/meta (one per photo, including the built-in wallpapers). */
object PhotoMetaStore {
  private val mem = java.util.concurrent.ConcurrentHashMap<String, PhotoMeta>()
  private fun dir(ctx: Context) = File(ctx.filesDir, "meta").apply { mkdirs() }
  fun keyOf(p: Photo) = p.file?.let { File(it).name } ?: ("asset_" + (p.asset ?: "").substringAfterLast('/'))
  private fun fileOf(ctx: Context, p: Photo) = File(dir(ctx), keyOf(p) + ".json")
  fun get(ctx: Context, p: Photo): PhotoMeta? = mem[keyOf(p)] ?: fileOf(ctx, p).takeIf { it.exists() }?.readText()?.let { PhotoMeta.parse(it) }
    ?.takeIf { it.version == PhotoMeta.VERSION }?.also { mem[keyOf(p)] = it }
  fun put(ctx: Context, p: Photo, m: PhotoMeta) { mem[keyOf(p)] = m; val f = fileOf(ctx, p); val t = File(f.path + ".tmp"); t.writeText(m.json()); t.renameTo(f) }
  fun delete(ctx: Context, p: Photo) { mem.remove(keyOf(p)); fileOf(ctx, p).delete() }
}

/**
 * Background worker: one low-priority thread does thumbnails + analysis once per photo, so uploads return immediately
 * and the UI thread never decodes. [onDone] is called (on the worker thread) after each photo.
 */
object Ingest {
  private val exec = Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "photo-ingest").apply { isDaemon = true } }
  private val queued = java.util.Collections.synchronizedSet(HashSet<String>())
  @Volatile var onDone: (() -> Unit)? = null
  @Volatile var faces: FaceFinder = PlatformFaces
  private val pending = java.util.concurrent.atomic.AtomicInteger(0)
  val busy get() = pending.get() > 0

  fun enqueue(ctx: Context, p: Photo) {
    val app = ctx.applicationContext; val k = PhotoMetaStore.keyOf(p)
    if (!queued.add(k)) return
    pending.incrementAndGet()
    exec.execute {
      try { process(app, p) } catch (_: Throwable) {} finally { queued.remove(k); pending.decrementAndGet(); onDone?.invoke() }
    }
  }

  /** Re-queues anything missing a thumbnail or analysis (e.g. photos from v4, or after an interrupted ingest). */
  fun backfill(ctx: Context, photos: List<Photo>) = photos.forEach { p ->
    val needThumb = p.file != null && !MyPhotos.thumbOf(ctx, p).exists()
    if (needThumb || PhotoMetaStore.get(ctx, p) == null) enqueue(ctx, p)
  }

  fun process(ctx: Context, p: Photo) {
    val bmp = (if (p.asset != null) ctx.assets.open(p.asset).use { android.graphics.BitmapFactory.decodeStream(it) }
      else android.graphics.BitmapFactory.decodeFile(p.file, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })) ?: return
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (p.file != null) android.graphics.BitmapFactory.decodeFile(p.file, o) else { o.outWidth = bmp.width; o.outHeight = bmp.height }
    if (p.file != null && !MyPhotos.thumbOf(ctx, p).exists()) MyPhotos.makeThumb(ctx, File(p.file), bmp)
    if (PhotoMetaStore.get(ctx, p) == null) PhotoMetaStore.put(ctx, p, Analyzer.analyze(bmp, o.outWidth, o.outHeight, faces))
    bmp.recycle()
  }
}

/** What the ambient screen shows next. */
sealed class SlideSpec {
  data class Full(val p: Photo) : SlideSpec()            // landscape, face-aware 16:9 crop + Ken Burns
  data class Contain(val p: Photo) : SlideSpec()         // single portrait (or faces too spread): blurred fill behind
  data class Pair2(val a: Photo, val b: Photo) : SlideSpec()   // two portraits side by side
}

object AmbientPlan {
  /** Builds one pass: skips flagged photos (unless that leaves nothing), pairs portraits in order, odd one out is shown alone. */
  fun build(list: List<Pair<Photo, PhotoMeta?>>): List<SlideSpec> {
    val good = list.filter { it.second?.flag == null }.ifEmpty { list }
    val out = ArrayList<SlideSpec>(); var waiting: Photo? = null
    for ((p, m) in good) {
      when {
        m?.portrait == true -> if (waiting == null) waiting = p else { out += SlideSpec.Pair2(waiting, p); waiting = null }
        m != null && !m.cropFits -> out += SlideSpec.Contain(p)
        else -> out += SlideSpec.Full(p)
      }
    }
    waiting?.let { out += SlideSpec.Contain(it) }
    return out
  }
}
