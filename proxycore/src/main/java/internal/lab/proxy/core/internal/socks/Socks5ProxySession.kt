package internal.lab.proxy.core.internal.socks

import internal.lab.proxy.core.internal.NativeProxyRuntime
import internal.lab.proxy.core.internal.ProxySession
import internal.lab.proxy.core.internal.StatsTracker
import internal.lab.proxy.core.internal.SocketRelay
import internal.lab.proxy.core.internal.closeQuietly
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Handles one SOCKS5 proxy client connection (no-auth CONNECT). */
internal class Socks5ProxySession(
    private val client: Socket,
    private val stats: StatsTracker,
    private val runtime: NativeProxyRuntime,
) : ProxySession {
  override fun run() {
    val input = client.getInputStream()
    val output = client.getOutputStream()
    var target: Socket? = null
    try {
      if (input.read() != SOCKS_VERSION) throw IOException("Unsupported SOCKS version")
      val nmethods = input.read()
      if (nmethods < 0) throw IOException("Connection closed during greeting")
      val methods = ByteArray(nmethods)
      readFully(input, methods)
      if (!methods.contains(NO_AUTH.toByte())) {
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE.toByte()))
        output.flush()
        return
      }
      output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
      output.flush()

      if (input.read() != SOCKS_VERSION) throw IOException("Unsupported SOCKS version")
      val command = input.read()
      val reserved = input.read()
      val addressType = input.read()
      if (command < 0 || reserved < 0 || addressType < 0) {
        throw IOException("Connection closed during command")
      }
      val (host, port) = readAddress(input, addressType)
      if (command != COMMAND_CONNECT) {
        sendCommandResponse(output, STATUS_COMMAND_UNSUPPORTED, addressType, null)
        return
      }

      target = connectTarget(host, port)
      val local = target.localSocketAddress as InetSocketAddress
      sendCommandResponse(output, STATUS_SUCCESS, addressType, local)
      SocketRelay(client, target, stats).run()
    } catch (error: Throwable) {
      stats.recordError(error)
    } finally {
      target?.let {
        runtime.untrackSocket(it)
        closeQuietly(it)
      }
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

  private fun readAddress(input: InputStream, addressType: Int): Pair<String, Int> {
    return when (addressType) {
      ATYP_IPV4 -> {
        val bytes = ByteArray(4)
        readFully(input, bytes)
        val host = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}." +
            "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
        host to readPort(input)
      }
      ATYP_DOMAIN -> {
        val length = input.read()
        if (length < 0) throw IOException("Connection closed reading domain length")
        val bytes = ByteArray(length)
        readFully(input, bytes)
        val host = String(bytes, Charsets.UTF_8)
        host to readPort(input)
      }
      ATYP_IPV6 -> {
        val bytes = ByteArray(16)
        readFully(input, bytes)
        val host = InetAddress.getByAddress(bytes).hostAddress
        host to readPort(input)
      }
      else -> throw IOException("Unsupported SOCKS address type: $addressType")
    }
  }

  private fun readPort(input: InputStream): Int {
    val bytes = ByteArray(2)
    readFully(input, bytes)
    return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
  }

  private fun readFully(input: InputStream, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read == -1) throw IOException("Connection closed while reading SOCKS message")
      offset += read
    }
  }

  private fun sendCommandResponse(
      output: OutputStream,
      status: Int,
      requestAddressType: Int,
      local: InetSocketAddress?,
  ) {
    val out = ByteArrayOutputStream()
    out.write(SOCKS_VERSION)
    out.write(status)
    out.write(0)
    if (local != null) {
      val address = local.address
      if (address is Inet6Address) {
        out.write(ATYP_IPV6)
        out.write(address.address)
      } else {
        out.write(ATYP_IPV4)
        out.write(address.address)
      }
      val port = local.port
      out.write((port shr 8) and 0xFF)
      out.write(port and 0xFF)
    } else {
      out.write(requestAddressType)
      when (requestAddressType) {
        ATYP_IPV4 -> out.write(byteArrayOf(0, 0, 0, 0))
        ATYP_IPV6 -> out.write(ByteArray(16))
        ATYP_DOMAIN -> out.write(0)
        else -> out.write(byteArrayOf(0, 0, 0, 0))
      }
      out.write(0)
      out.write(0)
    }
    output.write(out.toByteArray())
    output.flush()
  }

  private companion object {
    const val SOCKS_VERSION = 5
    const val NO_AUTH = 0
    const val NO_ACCEPTABLE = 0xFF
    const val COMMAND_CONNECT = 0x01
    const val ATYP_IPV4 = 0x01
    const val ATYP_DOMAIN = 0x03
    const val ATYP_IPV6 = 0x04
    const val STATUS_SUCCESS = 0x00
    const val STATUS_COMMAND_UNSUPPORTED = 0x07
    const val CONNECT_TIMEOUT_MILLIS = 10_000
  }
}
