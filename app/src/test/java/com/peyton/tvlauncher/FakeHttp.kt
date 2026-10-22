package com.peyton.tvlauncher

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Local stand-in for rest.api.bible / fums.api.bible: a routing lambda per request (plain ServerSocket, test-only). */
class FakeHttp(@Volatile var route: (Req) -> Res = { Res(404, "{}") }) : AutoCloseable {
  data class Req(val method: String, val path: String, val headers: Map<String, String>)
  data class Res(val status: Int, val body: String)

  val requests = java.util.concurrent.CopyOnWriteArrayList<Req>()
  private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
  val base = "http://127.0.0.1:${server.localPort}"

  init {
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val s = runCatching { server.accept() }.getOrNull() ?: break
        thread(isDaemon = true) { s.use { sock ->
          val inp = sock.getInputStream().bufferedReader(Charsets.UTF_8)
          val first = (inp.readLine() ?: return@use).split(" ")
          val h = HashMap<String, String>()
          while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; l.split(":", limit = 2).takeIf { it.size == 2 }?.let { h[it[0].trim().lowercase()] = it[1].trim() } }
          val req = Req(first[0], first.getOrElse(1) { "/" }, h); requests += req
          val r = route(req); val body = r.body.toByteArray()
          sock.getOutputStream().apply { write("HTTP/1.1 ${r.status} X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
        } }
      }
    }
  }
  override fun close() = server.close()
}

fun fixture(name: String): String = FakeHttp::class.java.classLoader!!.getResourceAsStream("apibible/$name")!!.bufferedReader().use { it.readText() }
