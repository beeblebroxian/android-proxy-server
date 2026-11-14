package internal.lab.proxy.core.internal.http

import internal.lab.proxy.core.internal.NativeProxyRuntime
import internal.lab.proxy.core.internal.ProxySession
import internal.lab.proxy.core.internal.StatsTracker
import internal.lab.proxy.core.internal.closeQuietly
import internal.lab.proxy.core.internal.SocketRelay
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch

/** Handles one HTTP proxy client connection (forward and CONNECT). */
internal class HttpProxySession(
    private val client: Socket,
    private val stats: StatsTracker,
    private val runtime: NativeProxyRuntime,
) : ProxySession {
  override fun run() {
    val clientInput = PushbackInputStream(client.getInputStream(), HEAD_PUSHBACK_SIZE)
    val head = try {
      readHttpHead(clientInput)
    } catch (error: Throwable) {
      sendError(client, STATUS_BAD_REQUEST)
      return
    }

    val target = try {
      HttpTargetParser.parse(head.method, head.uri, head.header("Host"))
    } catch (error: Throwable) {
      sendError(client, STATUS_BAD_REQUEST)
      return
    }

    val upstream = try {
      connectTarget(target.connectHost, target.port)
    } catch (error: Throwable) {
      stats.recordError(error)
      sendError(client, STATUS_BAD_GATEWAY)
      return
    }

    if (target.connectTunnel) {
      runTunnel(client, upstream, clientInput)
    } else {
      runForward(client, upstream, head, target, clientInput)
    }
  }

  private fun connectTarget(host: String, port: Int): Socket {
    val socket = Socket()
    try {
      socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
      socket.tcpNoDelay = true
      socket.keepAlive = true
    } catch (error: Throwable) {
      closeQuietly(socket)
      throw error
    }
    runtime.trackSocket(socket)
    return socket
  }

  private fun runTunnel(client: Socket, target: Socket, clientInput: InputStream) {
    try {
      val response =
          "HTTP/1.1 200 Connection Established\r\nContent-Length: 0\r\n\r\n"
      client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
      client.getOutputStream().flush()
    } catch (error: IOException) {
      closeQuietly(target)
      runtime.untrackSocket(target)
      return
    }
    try {
      SocketRelay(client, target, stats, clientInputOverride = clientInput).run()
    } finally {
      runtime.untrackSocket(target)
    }
  }

  private fun runForward(
      client: Socket,
      target: Socket,
      head: HttpHead,
      targetInfo: HttpTarget,
      clientInput: InputStream,
  ) {
    try {
      val forwarded = head.toForwardedHead(targetInfo)
      val targetOutput = target.getOutputStream()
      targetOutput.write(forwarded.toByteArray(Charsets.ISO_8859_1))
      targetOutput.flush()
    } catch (error: IOException) {
      closeQuietly(target)
      runtime.untrackSocket(target)
      return
    }

    try {
      if (isChunked(head)) {
        runChunkedRelay(client, target, clientInput)
      } else {
        SocketRelay(client, target, stats, clientInputOverride = clientInput).run()
      }
    } finally {
      runtime.untrackSocket(target)
    }
  }

  private fun runChunkedRelay(client: Socket, target: Socket, clientInput: InputStream) {
    val done = CountDownLatch(2)
    val clientToTarget =
        Thread.ofVirtual().name("proxy-http-c2t").start {
          try {
            forwardChunkedBody(clientInput, target.getOutputStream())
            shutdownOutputQuietly(target)
          } catch (_: IOException) {
          } catch (_: RuntimeException) {
          } finally {
            done.countDown()
          }
        }
    val targetToClient =
        Thread.ofVirtual().name("proxy-http-t2c").start {
          try {
            copyRaw(target.getInputStream(), client.getOutputStream()) { bytes ->
              stats.received(bytes)
            }
            shutdownOutputQuietly(client)
          } catch (_: IOException) {
          } catch (_: RuntimeException) {
          } finally {
            done.countDown()
          }
        }
    try {
      done.await()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    closeQuietly(client)
    closeQuietly(target)
  }

  private fun isChunked(head: HttpHead): Boolean {
    val transferEncoding = head.header("Transfer-Encoding") ?: return false
    return transferEncoding.lowercase().contains("chunked")
  }

  private fun forwardChunkedBody(clientInput: InputStream, targetOutput: OutputStream) {
    val bufferedInput = BufferedInputStream(clientInput, CHUNK_BUFFER)
    while (true) {
      val sizeLine = readLine(bufferedInput)
      val sizeHex = sizeLine.substringBefore(';').trim()
      val size = sizeHex.toInt(16)
      targetOutput.write((sizeLine + "\r\n").toByteArray(Charsets.ISO_8859_1))
      if (size == 0) {
        val trailers = mutableListOf<String>()
        while (true) {
          val line = readLine(bufferedInput)
          if (line.isEmpty()) break
          trailers.add(line)
        }
        for (line in trailers) {
          if (isProxyAuthorizationTrailer(line)) continue
          targetOutput.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
        }
        targetOutput.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        targetOutput.flush()
        return
      }
      var remaining = size
      val buffer = ByteArray(minOf(remaining, CHUNK_BUFFER))
      while (remaining > 0) {
        val toRead = minOf(buffer.size, remaining)
        val read = bufferedInput.read(buffer, 0, toRead)
        if (read == -1) throw IOException("Connection closed during chunked body")
        targetOutput.write(buffer, 0, read)
        remaining -= read
      }
      readLine(bufferedInput)
      targetOutput.write("\r\n".toByteArray(Charsets.ISO_8859_1))
      targetOutput.flush()
    }
  }

  private fun isProxyAuthorizationTrailer(line: String): Boolean {
    val colon = line.indexOf(':')
    if (colon <= 0) return false
    return line.substring(0, colon).trim().equals("Proxy-Authorization", ignoreCase = true)
  }

  private fun readLine(input: InputStream): String {
    val out = java.io.ByteArrayOutputStream()
    var cr = false
    while (true) {
      val b = input.read()
      if (b == -1) throw IOException("Connection closed while reading line")
      if (cr && b == '\n'.code) break
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
    return String(out.toByteArray(), Charsets.ISO_8859_1)
  }

  private fun copyRaw(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
    val buffer = ByteArray(RELAY_BUFFER)
    while (true) {
      val read = input.read(buffer)
      if (read == -1) break
      output.write(buffer, 0, read)
      output.flush()
      onBytes(read.toLong())
    }
  }

  private fun shutdownOutputQuietly(socket: Socket) {
    try {
      socket.shutdownOutput()
    } catch (_: IOException) {
    } catch (_: RuntimeException) {
    }
  }

  private fun sendError(client: Socket, statusLine: String) {
    try {
      val response = "$statusLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
      client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
      client.getOutputStream().flush()
    } catch (_: IOException) {
    } catch (_: RuntimeException) {
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MILLIS = 10_000
    const val CHUNK_BUFFER = 32 * 1024
    const val RELAY_BUFFER = 32 * 1024
    const val STATUS_BAD_REQUEST = "HTTP/1.1 400 Bad Request"
    const val STATUS_BAD_GATEWAY = "HTTP/1.1 502 Bad Gateway"
  }
}
