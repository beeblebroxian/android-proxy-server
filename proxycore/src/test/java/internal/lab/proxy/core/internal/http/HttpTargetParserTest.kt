package internal.lab.proxy.core.internal.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpTargetParserTest {
  @Test
  fun parsesAbsoluteForwardProxyUri() {
    val target = HttpTargetParser.parse("GET", "http://example.com:8081/path?q=1", null)

    assertEquals("example.com", target.host)
    assertEquals(8081, target.port)
    assertEquals("/path?q=1", target.originForm)
    assertFalse(target.connectTunnel)
  }

  @Test
  fun parsesRelativeUriFromIpv6HostHeader() {
    val target = HttpTargetParser.parse("GET", "/health", "[2001:db8::1]:8080")

    assertEquals("[2001:db8::1]", target.host)
    assertEquals(8080, target.port)
    assertEquals("/health", target.originForm)
  }

  @Test
  fun parsesConnectAuthority() {
    val target = HttpTargetParser.parse("CONNECT", "example.com:443", null)

    assertEquals("example.com", target.host)
    assertEquals(443, target.port)
    assertTrue(target.connectTunnel)
  }

  @Test
  fun rejectsRelativeUriWithoutHost() {
    assertFailsWith<IllegalArgumentException> { HttpTargetParser.parse("GET", "/", null) }
  }
}
