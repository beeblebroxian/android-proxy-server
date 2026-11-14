package internal.lab.proxy.core.internal.http

import internal.lab.proxy.core.ProxyConfig
import internal.lab.proxy.core.ProxyServerController
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HttpProxyIntegrationTest {
  private lateinit var controller: ProxyServerController
  private var proxyPort = 0

  @BeforeTest
  fun setUp() = runBlocking {
    val probe = ServerSocket(0)
    proxyPort = probe.localPort
    probe.close()
    controller = ProxyServerController()
    controller.start(
        ProxyConfig(
            httpEnabled = true,
            httpPort = proxyPort,
            socksEnabled = false,
            bindAddresses = listOf("127.0.0.1"),
        )
    )
  }

  @AfterTest
  fun tearDown() = runBlocking { controller.stop() }

  @Test
  fun forwardStripsProxyCredentialsAndPreservesAuthorization() {
    val target = TargetServer { request ->
      "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
    }
    try {
      val client = Socket()
      client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
      client.soTimeout = 5_000
      val out = client.getOutputStream()
      out.write(
          ("GET http://example.com/path?q=1 HTTP/1.1\r\n" +
                  "Host: example.com\r\n" +
                  "Proxy-Authorization: Basic synthetic-secret\r\n" +
                  "proxy-connection: keep-alive\r\n" +
                  "Authorization: Bearer token\r\n" +
                  "\r\n")
              .toByteArray(Charset.forName("ISO-8859-1")))
      out.flush()

      val response = readHttpResponse(client.getInputStream())
      assertTrue(response.head.startsWith("HTTP/1.1 200"), response.head)
      assertEquals("ok", response.body)

      val received = target.capturedRequest()
      assertTrue(received.contains("GET /path?q=1 HTTP/1.1"), received)
      assertTrue(received.contains("Authorization: Bearer token"), received)
      assertFalse(received.contains("Proxy-Authorization"), received)
      assertFalse(received.contains("proxy-connection"), received)
      assertFalse(received.contains("Proxy-Connection"), received)
      assertTrue(received.contains("Connection: close"), received)
    } finally {
      target.close()
    }
  }

  @Test
  fun connectTunnelsOpaqueBytes() {
    val target = TargetServer { request ->
      // Echo server: read whatever arrives and send it back, then close.
      null
    }
    try {
      val client = Socket()
      client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
      client.soTimeout = 5_000
      val out = client.getOutputStream()
      out.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray())
      out.flush()

      val head = readHttpHead(PushbackInputStream(client.getInputStream(), 8192))
      assertTrue(head.startsWith("HTTP/1.1 200 Connection Established"), head)

      val payload = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2e, 0x01, 0x00, 0x00, 0x2a, 0x03, 0x03)
      out.write(payload)
      out.flush()

      val echoed = ByteArray(payload.size)
      var offset = 0
      val input = client.getInputStream()
      while (offset < payload.size) {
        val read = input.read(echoed, offset, payload.size - offset)
        if (read == -1) break
        offset += read
      }
      assertTrue(echoed.contentEquals(payload), "Expected opaque bytes to be echoed")
    } finally {
      target.close()
    }
  }

  @Test
  fun chunkedTrailerStripsProxyAuthorization() {
    val target = TargetServer { request ->
      "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
    }
    try {
      val client = Socket()
      client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
      client.soTimeout = 5_000
      val out = client.getOutputStream()
      val body =
          "POST http://example.com/upload HTTP/1.1\r\n" +
              "Host: example.com\r\n" +
              "Transfer-Encoding: chunked\r\n" +
              "\r\n" +
              "4\r\nping\r\n" +
              "0\r\n" +
              "Proxy-Authorization: Basic synthetic-trailer-secret\r\n" +
              "X-Test-Trailer: retained\r\n" +
              "\r\n"
      out.write(body.toByteArray(Charset.forName("ISO-8859-1")))
      out.flush()

      val response = readHttpResponse(client.getInputStream())
      assertTrue(response.head.startsWith("HTTP/1.1 200"), response.head)

      val received = target.capturedRequest()
      assertTrue(received.contains("4\r\nping\r\n0\r\n"), received)
      assertFalse(received.contains("proxy-authorization", ignoreCase = true), received)
      assertTrue(received.contains("X-Test-Trailer: retained"), received)
    } finally {
      target.close()
    }
  }

  @Test
  fun badRequestReturns400() {
    val client = Socket()
    client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    client.soTimeout = 5_000
    try {
      client.getOutputStream().write("not an http request\r\n\r\n".toByteArray())
      client.getOutputStream().flush()
      val head = readHttpHead(PushbackInputStream(client.getInputStream(), 8192))
      assertTrue(head.startsWith("HTTP/1.1 400 Bad Request"), head)
    } finally {
      client.close()
    }
  }

  @Test
  fun relativeRequestWithoutHostReturns400() {
    val client = Socket()
    client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    client.soTimeout = 5_000
    try {
      client.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
      client.getOutputStream().flush()
      val head = readHttpHead(PushbackInputStream(client.getInputStream(), 8192))
      assertTrue(head.startsWith("HTTP/1.1 400 Bad Request"), head)
    } finally {
      client.close()
    }
  }

  @Test
  fun connectFailureReturns502() {
    // Reserve a port then close it so nothing listens there.
    val closed = ServerSocket(0)
    val deadPort = closed.localPort
    closed.close()

    val client = Socket()
    client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    client.soTimeout = 5_000
    try {
      client
          .getOutputStream()
          .write("CONNECT 127.0.0.1:$deadPort HTTP/1.1\r\n\r\n".toByteArray())
      client.getOutputStream().flush()
      val head = readHttpHead(PushbackInputStream(client.getInputStream(), 8192))
      assertTrue(head.startsWith("HTTP/1.1 502 Bad Gateway"), head)
    } finally {
      client.close()
    }
  }

  private fun readHttpHead(input: PushbackInputStream): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    val terminator = "\r\n\r\n".toByteArray(Charset.forName("ISO-8859-1"))
    while (true) {
      val read = input.read(buffer)
      if (read == -1) throw IOException("Connection closed before head")
      out.write(buffer, 0, read)
      val bytes = out.toByteArray()
      val end = indexOf(bytes, terminator)
      if (end >= 0) {
        val headEnd = end + terminator.size
        val leftover = bytes.size - headEnd
        if (leftover > 0) input.unread(bytes, headEnd, leftover)
        return String(bytes, 0, end, Charset.forName("ISO-8859-1"))
      }
    }
  }

  private fun readHttpResponse(input: InputStream): HttpResponse {
    val pushback = PushbackInputStream(input, 8192)
    val head = readHttpHead(pushback)
    val contentLength = extractContentLength(head)
    val body =
        if (contentLength != null) {
          readExact(pushback, contentLength)
        } else {
          ""
        }
    return HttpResponse(head, body)
  }

  private fun extractContentLength(head: String): Int? {
    for (line in head.split("\r\n")) {
      val lower = line.lowercase()
      if (lower.startsWith("content-length:")) {
        return line.substringAfter(':').trim().toIntOrNull()
      }
    }
    return null
  }

  private fun readExact(input: InputStream, count: Int): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    var remaining = count
    while (remaining > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size, remaining))
      if (read == -1) break
      out.write(buffer, 0, read)
      remaining -= read
    }
    return String(out.toByteArray(), Charset.forName("ISO-8859-1"))
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) continue@outer
      }
      return i
    }
    return -1
  }

  private data class HttpResponse(val head: String, val body: String)

  /** A single-connection target server that captures the request and replies. */
  private class TargetServer(
      private val responder: (String) -> String?,
  ) {
    private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    private val captured = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val thread: Thread

    init {
      thread =
          Thread({
            try {
              server.accept().use { socket ->
                socket.soTimeout = 5_000
                val request = readRequest(socket.getInputStream())
                captured.add(request)
                val response = responder(request)
                if (response != null) {
                  socket.getOutputStream().write(response.toByteArray(Charset.forName("ISO-8859-1")))
                  socket.getOutputStream().flush()
                } else {
                  // Echo mode: relay until client closes.
                  val input = socket.getInputStream()
                  val output = socket.getOutputStream()
                  val buffer = ByteArray(4096)
                  while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                  }
                }
              }
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
          }, "test-target").apply { isDaemon = true }
      thread.start()
    }

    val port: Int get() = server.localPort

    fun capturedRequest(): String {
      // Wait briefly for the request to arrive.
      val deadline = System.currentTimeMillis() + 5_000
      while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10)
      }
      return captured.firstOrNull() ?: ""
    }

    fun close() {
      server.close()
      thread.join(2_000)
    }

    private fun readRequest(input: InputStream): String {
      val pushback = PushbackInputStream(input, 8192)
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(4096)
      val terminator = "\r\n\r\n".toByteArray(Charset.forName("ISO-8859-1"))
      val headBytes = ByteArrayOutputStream()
      var headEnd = -1
      while (true) {
        val read = pushback.read(buffer)
        if (read == -1) return String(out.toByteArray(), Charset.forName("ISO-8859-1"))
        headBytes.write(buffer, 0, read)
        val bytes = headBytes.toByteArray()
        val end = indexOf(bytes, terminator)
        if (end >= 0) {
          headEnd = end + terminator.size
          val leftover = bytes.size - headEnd
          if (leftover > 0) pushback.unread(bytes, headEnd, leftover)
          break
        }
      }
      val head = String(headBytes.toByteArray(), 0, headEnd, Charset.forName("ISO-8859-1"))
      out.write(head.toByteArray(Charset.forName("ISO-8859-1")))
      val contentLength = extractContentLength(head)
      val isChunked = head.lowercase().contains("transfer-encoding: chunked")
      if (contentLength != null) {
        var remaining = contentLength
        while (remaining > 0) {
          val read = pushback.read(buffer, 0, minOf(buffer.size, remaining))
          if (read == -1) break
          out.write(buffer, 0, read)
          remaining -= read
        }
      } else if (isChunked) {
        while (true) {
          val line = readLine(pushback) ?: break
          out.write((line + "\r\n").toByteArray(Charset.forName("ISO-8859-1")))
          val sizeHex = line.substringBefore(';').trim()
          val size = sizeHex.toIntOrNull(16) ?: 0
          if (size == 0) {
            while (true) {
              val trailer = readLine(pushback) ?: break
              out.write((trailer + "\r\n").toByteArray(Charset.forName("ISO-8859-1")))
              if (trailer.isEmpty()) break
            }
            break
          }
          var remaining = size
          while (remaining > 0) {
            val read = pushback.read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
          }
          readLine(pushback)
          out.write("\r\n".toByteArray(Charset.forName("ISO-8859-1")))
        }
      }
      return String(out.toByteArray(), Charset.forName("ISO-8859-1"))
    }

    private fun readLine(input: InputStream): String? {
      val out = ByteArrayOutputStream()
      var cr = false
      while (true) {
        val b = input.read()
        if (b == -1) return if (out.size() == 0) null else String(out.toByteArray(), Charset.forName("ISO-8859-1"))
        if (cr && b == '\n'.code) return String(out.toByteArray(), Charset.forName("ISO-8859-1"))
        if (cr) {
          out.write('\r'.code)
          cr = false
        }
        if (b == '\r'.code) {
          cr = true
        } else {
          out.write(b)
        }
      }
    }
  }
}
