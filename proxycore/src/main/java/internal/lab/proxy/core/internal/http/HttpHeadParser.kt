package internal.lab.proxy.core.internal.http

import java.io.IOException
import java.io.InputStream

internal data class HttpHeader(val name: String, val value: String)

internal data class HttpHead(
    val method: String,
    val uri: String,
    val version: String,
    val headers: List<HttpHeader>,
) {
  fun header(name: String): String? {
    val lower = name.lowercase()
    return headers.firstOrNull { it.name.lowercase() == lower }?.value
  }

  fun hasHeader(name: String): Boolean = header(name) != null
}

/** Result of reading an HTTP head: the parsed head plus any bytes read past it. */
internal data class HttpHeadRead(val head: HttpHead, val leftover: ByteArray)

/**
 * Reads and parses an HTTP/1.x request head (request line + headers).
 *
 * Bytes read past the head terminator are returned as [HttpHeadRead.leftover] so the
 * caller can prepend them to the body (or tunneled payload) without losing data.
 */
internal fun readHttpHead(input: InputStream, maxBytes: Int = MAX_HEAD_BYTES): HttpHeadRead {
  val buffer = ByteArray(maxBytes + TERMINATOR.size)
  var position = 0
  val chunk = ByteArray(READ_CHUNK)
  var searchStart = 0
  while (true) {
    val read = input.read(chunk)
    if (read == -1) throw IOException("Connection closed before HTTP head completed")
    if (position + read > maxBytes) throw IOException("HTTP head exceeds $maxBytes bytes")
    System.arraycopy(chunk, 0, buffer, position, read)
    position += read
    val end = indexOf(buffer, searchStart, position, TERMINATOR)
    if (end >= 0) {
      val headEnd = end + TERMINATOR.size
      val leftoverCount = position - headEnd
      val leftover = if (leftoverCount > 0) buffer.copyOfRange(headEnd, position) else ByteArray(0)
      val headText = String(buffer, 0, end, Charsets.ISO_8859_1)
      return HttpHeadRead(parseHttpHead(headText), leftover)
    }
    searchStart = maxOf(0, position - (TERMINATOR.size - 1))
  }
}

private fun parseHttpHead(headText: String): HttpHead {
  val lines = headText.split("\r\n")
  require(lines.isNotEmpty()) { "Empty HTTP head" }
  val requestLine = lines[0]
  val parts = requestLine.split(" ")
  require(parts.size == 3) { "Malformed HTTP request line: $requestLine" }
  val method = parts[0]
  val uri = parts[1]
  val version = parts[2]
  require(method.isNotBlank() && uri.isNotBlank() && version.startsWith("HTTP/")) {
    "Malformed HTTP request line: $requestLine"
  }
  val headers = mutableListOf<HttpHeader>()
  for (i in 1 until lines.size) {
    val line = lines[i]
    if (line.isEmpty()) continue
    val colon = line.indexOf(':')
    require(colon > 0) { "Malformed HTTP header: $line" }
    val name = line.substring(0, colon).trim()
    val value = line.substring(colon + 1).trim()
    headers.add(HttpHeader(name, value))
  }
  return HttpHead(method, uri, version, headers)
}

/** Builds the forwarded request head for a non-tunnel request. */
internal fun HttpHead.toForwardedHead(target: HttpTarget): String {
  val builder = StringBuilder()
  builder.append(method).append(' ').append(target.originForm).append(' ').append(version).append("\r\n")
  for (header in headers) {
    val lower = header.name.lowercase()
    if (lower == "proxy-connection" || lower == "proxy-authorization" || lower == "connection") {
      continue
    }
    builder.append(header.name).append(": ").append(header.value).append("\r\n")
  }
  builder.append("Connection: close\r\n\r\n")
  return builder.toString()
}

private fun indexOf(haystack: ByteArray, fromIndex: Int, toIndex: Int, needle: ByteArray): Int {
  if (needle.isEmpty() || toIndex - fromIndex < needle.size) return -1
  val limit = toIndex - needle.size
  outer@ for (i in fromIndex..limit) {
    for (j in needle.indices) {
      if (haystack[i + j] != needle[j]) continue@outer
    }
    return i
  }
  return -1
}

private const val MAX_HEAD_BYTES = 64 * 1024
private const val READ_CHUNK = 8192
private val TERMINATOR = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
