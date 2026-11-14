package internal.lab.proxy.core.internal

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch

/** Bidirectional blocking relay between a client and a target socket. */
internal class SocketRelay(
    private val client: Socket,
    private val target: Socket,
    private val stats: StatsTracker,
    private val clientInputOverride: InputStream? = null,
) {
  private val clientInput: InputStream = clientInputOverride ?: client.getInputStream()

  fun run() {
    val done = CountDownLatch(2)
    val clientToTarget =
        Thread.ofVirtual().name("proxy-relay-c2t").start {
          copy(clientInput, target.getOutputStream(), target, sent = true)
          done.countDown()
        }
    val targetToClient =
        Thread.ofVirtual().name("proxy-relay-t2c").start {
          copy(target.getInputStream(), client.getOutputStream(), client, sent = false)
          done.countDown()
        }
    try {
      done.await()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    closeQuietly(client)
    closeQuietly(target)
  }

  private fun copy(input: InputStream, output: OutputStream, destination: Socket, sent: Boolean) {
    try {
      val buffer = ByteArray(BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
        output.flush()
        if (sent) stats.sent(read.toLong()) else stats.received(read.toLong())
      }
      shutdownOutputQuietly(destination)
    } catch (_: IOException) {
      // Peer closed or errored; the other direction will finish on its own.
    } catch (_: RuntimeException) {
      // Socket closed during shutdown.
    }
  }

  private fun shutdownOutputQuietly(socket: Socket) {
    try {
      socket.shutdownOutput()
    } catch (_: IOException) {
      // Already closed.
    } catch (_: RuntimeException) {
      // Already closed.
    }
  }

  private companion object {
    const val BUFFER_SIZE = 16 * 1024
  }
}

internal fun closeQuietly(socket: Socket?) {
  if (socket == null) return
  try {
    socket.close()
  } catch (_: IOException) {
    // Already closed.
  } catch (_: RuntimeException) {
    // Already closed.
  }
}

internal fun closeQuietly(server: java.net.ServerSocket?) {
  if (server == null) return
  try {
    server.close()
  } catch (_: IOException) {
    // Already closed.
  } catch (_: RuntimeException) {
    // Already closed.
  }
}
