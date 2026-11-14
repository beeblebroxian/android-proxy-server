package internal.lab.proxy.core.internal.socks

import internal.lab.proxy.core.ProxyConfig
import internal.lab.proxy.core.internal.NativeProxyRuntime
import internal.lab.proxy.core.internal.StatsTracker
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class Socks5ProxyIntegrationTest {
  private lateinit var runtime: NativeProxyRuntime
  private var proxyPort = 0

  @BeforeTest
  fun setUp() = runBlocking {
    val probe = ServerSocket(0)
    proxyPort = probe.localPort
    probe.close()
    runtime = NativeProxyRuntime(StatsTracker(), enforceSelfConnectionGuard = false)
    runtime.start(
        ProxyConfig(
            httpEnabled = false,
            socksEnabled = true,
            socksPort = proxyPort,
            bindAddresses = listOf("127.0.0.1"),
        )
    )
  }

  @AfterTest
  fun tearDown() = runBlocking { runtime.stop() }

  @Test
  fun noAuthConnectRelaysBytes() {
    val target = EchoServer()
    try {
      val client = Socket()
      client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
      client.soTimeout = 5_000
      val out = client.getOutputStream()
      val input = client.getInputStream()

      out.write(byteArrayOf(0x05, 0x01, 0x00))
      out.flush()
      assertEquals(0x05, input.read())
      assertEquals(0x00, input.read())

      val connect =
          byteArrayOf(
              0x05, 0x01, 0x00, 0x01,
              127.toByte(), 0, 0, 1,
          ) + portBytes(target.port)
      out.write(connect)
      out.flush()

      val rep = readSocksReply(input)
      assertEquals(0x00, rep)

      val payload = byteArrayOf(1, 2, 3, 4, 5)
      out.write(payload)
      out.flush()
      val echoed = ByteArray(payload.size)
      readFully(input, echoed)
      assertTrue(payload.contentEquals(echoed), "Expected echoed bytes")
    } finally {
      target.close()
    }
  }

  @Test
  fun greetingWithoutNoAuthIsRejected() {
    val client = Socket()
    client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    client.soTimeout = 5_000
    try {
      client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
      client.getOutputStream().flush()
      val input = client.getInputStream()
      assertEquals(0x05, input.read())
      assertEquals(0xFF.toByte(), input.read().toByte())
    } finally {
      client.close()
    }
  }

  @Test
  fun connectWithDomainAddress() {
    val target = EchoServer(InetAddress.getByName("localhost"))
    try {
      val client = Socket()
      client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
      client.soTimeout = 5_000
      val out = client.getOutputStream()
      val input = client.getInputStream()

      out.write(byteArrayOf(0x05, 0x01, 0x00))
      out.flush()
      input.read()
      input.read()

      val domain = "localhost".toByteArray()
      val connect =
          byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()) + domain + portBytes(target.port)
      out.write(connect)
      out.flush()

      val rep = readSocksReply(input)
      assertEquals(0x00, rep)
    } finally {
      target.close()
    }
  }

  @Test
  fun nonConnectCommandIsRejected() {
    val client = Socket()
    client.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
    client.soTimeout = 5_000
    try {
      val out = client.getOutputStream()
      val input = client.getInputStream()
      out.write(byteArrayOf(0x05, 0x01, 0x00))
      out.flush()
      input.read()
      input.read()

      // BIND command (0x02) to 127.0.0.1:port
      val bind =
          byteArrayOf(0x05, 0x02, 0x00, 0x01, 127.toByte(), 0, 0, 1) + portBytes(8080)
      out.write(bind)
      out.flush()

      val response = ByteArray(4)
      readFully(input, response)
      assertEquals(0x05, response[0])
      assertEquals(0x07, response[1])
    } finally {
      client.close()
    }
  }

  private fun portBytes(port: Int): ByteArray =
      byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())

  private fun readFully(input: InputStream, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read == -1) throw IOException("Connection closed while reading")
      offset += read
    }
  }

  /** Reads a full SOCKS5 reply and returns the REP status byte. */
  private fun readSocksReply(input: InputStream): Int {
    val head = ByteArray(4)
    readFully(input, head)
    assertEquals(0x05, head[0])
    val rep = head[1].toInt() and 0xFF
    when (head[3].toInt() and 0xFF) {
      0x01 -> readFully(input, ByteArray(6))
      0x04 -> readFully(input, ByteArray(18))
      0x03 -> {
        val len = input.read()
        if (len == -1) throw IOException("Connection closed while reading domain")
        readFully(input, ByteArray(len + 2))
      }
      else -> throw IOException("Unexpected SOCKS address type")
    }
    return rep
  }

  private class EchoServer(
      bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
  ) {
    private val server = ServerSocket(0, 1, bindAddress)
    private val thread: Thread

    init {
      thread =
          Thread({
            try {
              server.accept().use { socket ->
                socket.soTimeout = 5_000
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
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
          }, "test-echo").apply { isDaemon = true }
      thread.start()
    }

    val port: Int get() = server.localPort

    fun close() {
      server.close()
      thread.join(2_000)
    }
  }
}
