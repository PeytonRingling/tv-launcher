package com.peyton.tvlauncher

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoServerTest {
  private val ctx get() = RuntimeEnvironment.getApplication()

  @Before fun setup() { Ingest.faces = FaceFinder { emptyList() }; MyPhotos.list(ctx).forEach { MyPhotos.delete(ctx, it) }; MyPhotos.resetCount() }

  private fun post(base: String, pin: String, bytes: ByteArray, batch: String? = null): Pair<Int, String> {
    val c = URL("$base/$pin/upload").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/octet-stream"); c.setFixedLengthStreamingMode(bytes.size)
    batch?.let { c.setRequestProperty("X-Batch", it) }
    c.outputStream.use { it.write(bytes) }
    val code = c.responseCode; val msg = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    return code to msg
  }
  private fun waitIngest() { val end = System.currentTimeMillis() + 60_000; Thread.sleep(50); while (Ingest.busy && System.currentTimeMillis() < end) Thread.sleep(25) }

  @Test fun uploadFlow() {
    var saved = 0; var lastBatch: Pair<String?, Int>? = null
    val srv = PhotoServer.start(ctx) { b, t -> saved++; lastBatch = b to t }!!
    try {
      val base = "http://127.0.0.1:${srv.listeningPort}"
      fun get(path: String): Pair<Int, String> { val c = URL(base + path).openConnection() as HttpURLConnection
        val code = c.responseCode; val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""; return code to body }
      assertEquals(403, get("/000000").first)
      val page = get("/${srv.pin}"); assertEquals(200, page.first); assertTrue(page.second.contains("Choose photos"))
      val (code, msg) = post(base, srv.pin, ctx.assets.open("wallpapers/fjord.jpg").readBytes(), "abc123:7")
      println("upload -> $code $msg"); assertEquals(200, code)
      assertEquals(1, saved); assertEquals("abc123" to 7, lastBatch)
      waitIngest()
      val list = MyPhotos.list(ctx); assertEquals(1, list.size)
      assertTrue(MyPhotos.thumbOf(ctx, list[0]).exists())
      val meta = PhotoMetaStore.get(ctx, list[0])!!
      println("meta: ${meta.w}x${meta.h} sharp=${meta.sharpness} flag=${meta.flag} crop=${meta.crop}")
      MyPhotos.delete(ctx, list[0]); assertEquals(0, MyPhotos.list(ctx).size); assertEquals(null, PhotoMetaStore.get(ctx, list[0]))
      // A PNG (not what the phone page sends) takes the convert path and still works.
      val png = ByteArrayOutputStream().also { Photos.decode(ctx, Photo(asset = "wallpapers/coast.jpg"), 1920)!!.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
      val r2 = post(base, srv.pin, png); assertEquals(200, r2.first); assertEquals("ok (converted)", r2.second)
    } finally { srv.stop() }
  }

  /** What the phone page sends: upright JPEG, long edge 2560 (landscape) / 2160 tall (portrait), q 0.85. */
  private fun phoneJpegs(n: Int): List<ByteArray> {
    val src = Photos.builtins(ctx).map { Photos.decode(ctx, it, 4000)!! }
    return (0 until n).map { i ->
      val b = src[i % src.size]
      val portrait = i % 4 == 3
      val out = if (portrait) Bitmap.createScaledBitmap(Bitmap.createBitmap(b, b.width / 3, 0, b.height * 9 / 16, b.height), 1215, 2160, true)
        else Bitmap.createScaledBitmap(b, 2560, 1440, true)
      ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
  }

  @Test fun throughput() {
    val n = 30
    val files = phoneJpegs(n)
    val mb = files.sumOf { it.size } / 1048576.0
    val srv = PhotoServer.start(ctx) { _, _ -> }!!
    val report = StringBuilder()
    try {
      val base = "http://127.0.0.1:${srv.listeningPort}"
      fun run(par: Int): Double {
        val ok = AtomicInteger(); val pool = Executors.newFixedThreadPool(par)
        val t0 = System.nanoTime()
        files.forEach { f -> pool.execute { if (post(base, srv.pin, f, "b$par:$n").first == 200) ok.incrementAndGet() } }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.MINUTES)
        val s = (System.nanoTime() - t0) / 1e9
        assertEquals(n, ok.get())
        return s
      }
      val s1 = run(1)
      waitIngest(); MyPhotos.list(ctx).forEach { MyPhotos.delete(ctx, it) }
      val s5 = run(5)
      assertEquals(n, MyPhotos.list(ctx).size)
      // TV-side cost per photo: v4 decoded + re-encoded + thumbnailed inside the request; v4.1 just moves the file.
      val tmp = File(ctx.cacheDir, "bench.jpg")
      val legacyMs = files.take(8).map { f -> tmp.writeBytes(f); val t = System.nanoTime()
        val b = MyPhotos.decodeScaled(tmp, MyPhotos.LONG_EDGE)!!; val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 88, o)
        val th = Bitmap.createScaledBitmap(b, 480, 270, true); th.compress(Bitmap.CompressFormat.JPEG, 82, ByteArrayOutputStream())
        (System.nanoTime() - t) / 1e6 }.average()
      val fastMs = files.take(8).map { f -> tmp.writeBytes(f); val t = System.nanoTime(); val r = MyPhotos.store(ctx, tmp); assertTrue(r.fastPath); (System.nanoTime() - t) / 1e6 }.average()
      val t0 = System.nanoTime(); waitIngest()
      val ingestMs = MyPhotos.list(ctx).take(6).map { p -> PhotoMetaStore.delete(ctx, p); MyPhotos.thumbOf(ctx, p).delete(); val t = System.nanoTime(); Ingest.process(ctx, p); (System.nanoTime() - t) / 1e6 }.average()
      report.appendLine("Photo upload throughput (Robolectric JVM, loopback; $n phone-sized JPEGs, %.1f MB total)".format(mb))
      report.appendLine("  1 at a time : %.2f s  = %.1f photos/s, %.1f MB/s".format(s1, n / s1, mb / s1))
      report.appendLine("  5 parallel  : %.2f s  = %.1f photos/s, %.1f MB/s  (%.1fx)".format(s5, n / s5, mb / s5, s1 / s5))
      report.appendLine("TV-side work inside each request:")
      report.appendLine("  v4   decode + re-encode + thumbnail : %.0f ms/photo (serialized by a lock)".format(legacyMs))
      report.appendLine("  v4.1 fast path (validate + move)    : %.1f ms/photo (parallel)".format(fastMs))
      report.appendLine("Background worker (thumbnail + faces + sharpness, after the response): %.0f ms/photo".format(ingestMs))
      println(report)
      File("../screens/v3").mkdirs(); File("../screens/v3/throughput.txt").writeText(report.toString())
      assertTrue("parallel should not be slower", s5 < s1 * 1.1)
      assertTrue(fastMs * 5 < legacyMs)
    } finally { srv.stop() }
  }

  /** The phone page's script parses, and its resize targets match the spec (landscape 2560 long edge, portraits 2160 tall, never upscale). */
  @Test fun phonePageScript() {
    val srv = PhotoServer.start(ctx) { _, _ -> }!!
    try {
      val html = URL("http://127.0.0.1:${srv.listeningPort}/${srv.pin}").readText()
      val js = Regex("<script>([\\s\\S]*)</script>").find(html)!!.groupValues[1]
      val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).exists() } ?: return println("node not available; skipped")
      val f = File.createTempFile("page", ".js"); f.writeText(js)
      val chk = ProcessBuilder(node, "--check", f.path).redirectErrorStream(true).start(); val out = chk.inputStream.bufferedReader().readText()
      assertEquals("syntax: $out", 0, chk.waitFor())
      val fn = Regex("function target\\(w,h\\)\\{[^}]*\\}").find(js)!!.value
      val p = ProcessBuilder(node, "-e", "const LAND=2560,PORT=2160;$fn;console.log(JSON.stringify([target(4032,3024),target(3024,4032),target(1200,800),target(8000,2000)]))").redirectErrorStream(true).start()
      val r = p.inputStream.bufferedReader().readText().trim(); p.waitFor()
      println("resize targets: $r")
      assertEquals("[[2560,1920],[1620,2160],[1200,800],[2560,640]]", r)
      assertTrue(js.contains("OffscreenCanvas") && js.contains("PAR=5") && js.contains("X-Batch"))
    } finally { srv.stop() }
  }
}
