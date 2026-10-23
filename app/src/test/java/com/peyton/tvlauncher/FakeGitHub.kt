package com.peyton.tvlauncher

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Tiny local stand-in for api.github.com + release downloads (plain ServerSocket, test-only, one request per connection). */
class FakeGitHub : AutoCloseable {
  @Volatile var json = "{}"
  @Volatile var status = 200
  @Volatile var headers: Map<String, String> = emptyMap()
  @Volatile var apk = ByteArray(0)
  @Volatile var apkStatus = 200
  val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
  private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
  val base = "http://127.0.0.1:${server.localPort}"

  init {
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val s = runCatching { server.accept() }.getOrNull() ?: break
        thread(isDaemon = true) { s.use { sock ->
          val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
          val path = (inp.readLine() ?: return@use).split(" ").getOrElse(1) { "/" }
          while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break }
          paths += path
          val isApk = path.startsWith("/dl/")
          val code = if (isApk) apkStatus else status
          val body = if (code != 200 && isApk) ByteArray(0) else if (isApk) apk else json.toByteArray()
          val extra = if (isApk) "" else headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
          val out = sock.getOutputStream()
          out.write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
          out.write(body); out.flush()
        } }
      }
    }
  }
  override fun close() = server.close()
}
