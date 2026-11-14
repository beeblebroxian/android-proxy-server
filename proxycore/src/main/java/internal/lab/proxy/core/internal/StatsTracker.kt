package internal.lab.proxy.core.internal

import internal.lab.proxy.core.ProxyStats
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class StatsTracker {
  private companion object {
    const val LOCAL_LOOP_MESSAGE = "Blocked a connection from this device to prevent a proxy loop"
    const val FLUSH_THRESHOLD_BYTES = 1L * 1024 * 1024
  }

  private val mutableStats =
      MutableStateFlow(
          ProxyStats(
              running = false,
              activeConnections = 0,
              totalConnections = 0,
              sentBytes = 0,
              receivedBytes = 0,
              lastError = null,
          )
      )

  val stats: StateFlow<ProxyStats> = mutableStats.asStateFlow()

  private val pendingSentBytes = AtomicLong(0)
  private val pendingReceivedBytes = AtomicLong(0)

  fun starting() {
    pendingSentBytes.set(0)
    pendingReceivedBytes.set(0)
    mutableStats.update {
      it.copy(
          running = false,
          activeConnections = 0,
          totalConnections = 0,
          sentBytes = 0,
          receivedBytes = 0,
          lastError = null,
      )
    }
  }

  fun running() = mutableStats.update { it.copy(running = true, lastError = null) }

  fun localLoopBlocked() =
      mutableStats.update {
        if (it.lastError == LOCAL_LOOP_MESSAGE) it else it.copy(lastError = LOCAL_LOOP_MESSAGE)
      }

  fun stopped() {
    flushPendingBytes()
    mutableStats.update { it.copy(running = false, activeConnections = 0) }
  }

  fun failed(error: Throwable) =
      mutableStats.update {
        it.copy(running = false, lastError = error.message ?: error.javaClass.simpleName)
      }

  fun recordError(error: Throwable) =
      mutableStats.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }

  fun connectionOpened() =
      mutableStats.update {
        it.copy(
            activeConnections = it.activeConnections + 1,
            totalConnections = it.totalConnections + 1,
        )
      }

  fun connectionClosed() =
      mutableStats.update { it.copy(activeConnections = (it.activeConnections - 1).coerceAtLeast(0)) }

  fun sent(bytes: Long) {
    if (bytes <= 0) return
    val total = pendingSentBytes.addAndGet(bytes)
    if (total >= FLUSH_THRESHOLD_BYTES) flushPendingBytes()
  }

  fun received(bytes: Long) {
    if (bytes <= 0) return
    val total = pendingReceivedBytes.addAndGet(bytes)
    if (total >= FLUSH_THRESHOLD_BYTES) flushPendingBytes()
  }

  @Synchronized
  private fun flushPendingBytes() {
    val sent = pendingSentBytes.getAndSet(0)
    val received = pendingReceivedBytes.getAndSet(0)
    if (sent == 0L && received == 0L) return
    mutableStats.update {
      it.copy(sentBytes = it.sentBytes + sent, receivedBytes = it.receivedBytes + received)
    }
  }
}
