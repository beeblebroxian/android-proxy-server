package internal.lab.proxy.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class StatsTrackerTest {
  @Test
  fun idleTrackerDoesNotCreateTrafficOrConnections() {
    val tracker = StatsTracker()

    tracker.starting()
    tracker.running()

    assertEquals(0, tracker.stats.value.activeConnections)
    assertEquals(0, tracker.stats.value.totalConnections)
    assertEquals(0, tracker.stats.value.sentBytes)
    assertEquals(0, tracker.stats.value.receivedBytes)
  }

  @Test
  fun startingNewSessionClearsPreviousCounters() {
    val tracker = StatsTracker()
    tracker.connectionOpened()
    tracker.sent(4)
    tracker.received(4)

    tracker.starting()
    tracker.running()

    assertEquals(0, tracker.stats.value.activeConnections)
    assertEquals(0, tracker.stats.value.totalConnections)
    assertEquals(0, tracker.stats.value.sentBytes)
    assertEquals(0, tracker.stats.value.receivedBytes)
  }
}
