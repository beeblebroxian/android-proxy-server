package internal.lab.proxy.core.internal

import internal.lab.proxy.core.ProxyConfig
import internal.lab.proxy.core.internal.http.HttpProxySession
import internal.lab.proxy.core.internal.socks.Socks5ProxySession
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Native blocking-socket proxy runtime replacing the Netty-based one. */
internal class NativeProxyRuntime(private val stats: StatsTracker) {
  private val lock = Any()
  private val serverSockets = mutableListOf<ServerSocket>()
  private val acceptThreads = mutableListOf<Thread>()
  private val trackedSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
  private val healthProbes = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
  private var pool: ExecutorService? = null
  @Volatile private var running = false

  suspend fun start(config: ProxyConfig) {
    val addresses = resolveBindAddresses(config.bindAddresses)
    val newPool =
        Executors.newCachedThreadPool { runnable ->
          Thread(runnable, "proxy-worker").apply { isDaemon = true }
        }
    synchronized(lock) {
      pool = newPool
      running = true
    }
    try {
      if (config.httpEnabled) {
        bindAll("HTTP", config.httpPort, addresses) { socket ->
          HttpProxySession(socket, stats, this)
        }
        verifyListener("HTTP", config.httpPort)
      }
      if (config.socksEnabled) {
        bindAll("SOCKS", config.socksPort, addresses) { socket ->
          Socks5ProxySession(socket, stats, this)
        }
        verifyListener("SOCKS", config.socksPort)
      }
    } catch (error: Throwable) {
      stop()
      throw error
    }
  }

  suspend fun stop() {
    val servers: List<ServerSocket>
    val threads: List<Thread>
    val sockets: List<Socket>
    val executor: ExecutorService?
    synchronized(lock) {
      servers = serverSockets.toList()
      threads = acceptThreads.toList()
      sockets = trackedSockets.toList()
      executor = pool
      serverSockets.clear()
      acceptThreads.clear()
      trackedSockets.clear()
      pool = null
      running = false
    }
    servers.forEach { closeQuietly(it) }
    sockets.forEach { closeQuietly(it) }
    executor?.shutdownNow()
    threads.forEach { it.interrupt() }
    threads.forEach { thread ->
      try {
        thread.join(ACCEPT_JOIN_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  internal fun trackSocket(socket: Socket) {
    trackedSockets.add(socket)
  }

  internal fun untrackSocket(socket: Socket) {
    trackedSockets.remove(socket)
  }

  private fun resolveBindAddresses(bindAddresses: List<String>): List<String> {
    if (bindAddresses.isEmpty()) return listOf(LISTEN_HOST)
    return (listOf(LOOPBACK_HOST) + bindAddresses).distinct()
  }

  private fun bindAll(
      protocol: String,
      port: Int,
      addresses: List<String>,
      factory: (Socket) -> ProxySession,
  ) {
    for (address in addresses) {
      bind(protocol, port, address, factory)
    }
  }

  private fun bind(
      protocol: String,
      port: Int,
      address: String,
      factory: (Socket) -> ProxySession,
  ) {
    val server = ServerSocket()
    try {
      server.reuseAddress = true
      server.bind(InetSocketAddress(address, port))
    } catch (error: IOException) {
      closeQuietly(server)
      throw IllegalStateException("$protocol port $port is unavailable or already in use", error)
    }
    val thread =
        Thread({ acceptLoop(server, factory) }, "proxy-accept-$protocol-$port-$address").apply {
          isDaemon = true
        }
    synchronized(lock) {
      serverSockets.add(server)
      acceptThreads.add(thread)
    }
    thread.start()
  }

  private fun acceptLoop(server: ServerSocket, factory: (Socket) -> ProxySession) {
    while (running) {
      val client = accept(server) ?: break
      try {
        client.tcpNoDelay = true
        client.keepAlive = true
        if (completeHealthProbe(client)) {
          closeQuietly(client)
          continue
        }
        if (isSelfConnection(client.localSocketAddress, client.remoteSocketAddress)) {
          stats.localLoopBlocked()
          closeQuietly(client)
          continue
        }
        val executor = pool
        if (executor == null || !running) {
          closeQuietly(client)
          break
        }
        trackSocket(client)
        stats.connectionOpened()
        try {
          executor.execute {
            try {
              factory(client).run()
            } catch (error: Throwable) {
              stats.recordError(error)
            } finally {
              untrackSocket(client)
              stats.connectionClosed()
              closeQuietly(client)
            }
          }
        } catch (_: RejectedExecutionException) {
          untrackSocket(client)
          stats.connectionClosed()
          closeQuietly(client)
        }
      } catch (error: Throwable) {
        stats.recordError(error)
        closeQuietly(client)
      }
    }
  }

  private fun accept(server: ServerSocket): Socket? {
    return try {
      server.accept()
    } catch (_: IOException) {
      null
    }
  }

  private fun completeHealthProbe(client: Socket): Boolean {
    val remote = client.remoteSocketAddress as? InetSocketAddress ?: return false
    if (!remote.address.isLoopbackAddress) return false
    val completion = healthProbes.remove(remote.port) ?: return false
    completion.complete(Unit)
    return true
  }

  private suspend fun verifyListener(protocol: String, port: Int) {
    val completion = CompletableDeferred<Unit>()
    Socket().use { socket ->
      socket.bind(InetSocketAddress(LOOPBACK_HOST, 0))
      val sourcePort = socket.localPort
      healthProbes[sourcePort] = completion
      try {
        socket.connect(InetSocketAddress(LOOPBACK_HOST, port), HEALTH_CHECK_TIMEOUT_MILLIS)
        withTimeout(HEALTH_CHECK_TIMEOUT_MILLIS.toLong()) { completion.await() }
      } catch (error: TimeoutCancellationException) {
        throw IllegalStateException("$protocol listener self-test failed on port $port", error)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        throw IllegalStateException("$protocol listener self-test failed on port $port", error)
      } finally {
        healthProbes.remove(sourcePort)
      }
    }
  }

  private companion object {
    const val LISTEN_HOST = "0.0.0.0"
    const val LOOPBACK_HOST = "127.0.0.1"
    const val HEALTH_CHECK_TIMEOUT_MILLIS = 2_000
    const val ACCEPT_JOIN_MILLIS = 1_000L
  }
}

internal interface ProxySession {
  fun run()
}
