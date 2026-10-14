package server

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Arrays

/**
 * A growable byte buffer that writes compact JSON straight to UTF-8 bytes.
 *
 * Each event-loop thread owns one (see [[Db]]) and resets it for every response, so building a response
 * allocates nothing but the final `toByteArray` copy. Strings arrive from SQLite as raw UTF-8 bytes and are
 * escaped byte-by-byte: multi-byte UTF-8 sequences never contain bytes below 0x80, so they pass through as-is.
 */
final class JsonOut(initialCapacity: Int):
  private var buf = new Array[Byte](initialCapacity)
  private var len = 0

  def reset(): JsonOut =
    len = 0
    this

  def toByteArray: Array[Byte] = Arrays.copyOf(buf, len)

  private def ensure(extra: Int): Unit =
    if len + extra > buf.length then buf = Arrays.copyOf(buf, math.max(buf.length * 2, len + extra))

  /** Appends bytes that are already valid JSON (constants such as `{"post":`). */
  def raw(bytes: Array[Byte]): JsonOut =
    ensure(bytes.length)
    System.arraycopy(bytes, 0, buf, len, bytes.length)
    len += bytes.length
    this

  def byte(b: Byte): JsonOut =
    ensure(1)
    buf(len) = b
    len += 1
    this

  def long(value: Long): JsonOut =
    ensure(20)
    var v = value
    if v < 0 then
      buf(len) = '-'
      len += 1
      v = -v
    val start = len
    while
      buf(len) = ('0' + (v % 10)).toByte
      len += 1
      v /= 10
      v != 0
    do ()
    var i = start
    var j = len - 1
    while i < j do
      val t = buf(i)
      buf(i) = buf(j)
      buf(j) = t
      i += 1
      j -= 1
    this

  def bool(value: Boolean): JsonOut = raw(if value then JsonOut.True else JsonOut.False)

  def string(s: String): JsonOut = string(s.getBytes(UTF_8))

  /** Writes a JSON string from UTF-8 bytes, escaping `"`, `\` and control characters. */
  def string(utf8: Array[Byte]): JsonOut =
    ensure(utf8.length + 2)
    buf(len) = '"'
    len += 1
    var runStart = 0
    var i        = 0
    while i < utf8.length do
      val b = utf8(i)
      if b == '"' || b == '\\' || (b >= 0 && b < 0x20) then
        copy(utf8, runStart, i - runStart)
        escape(b)
        runStart = i + 1
      i += 1
    copy(utf8, runStart, utf8.length - runStart)
    byte('"')

  private def copy(src: Array[Byte], from: Int, n: Int): Unit =
    if n > 0 then
      ensure(n)
      System.arraycopy(src, from, buf, len, n)
      len += n

  private def escape(b: Byte): Unit =
    ensure(6)
    buf(len) = '\\'
    b match
      case '"'  => buf(len + 1) = '"'; len += 2
      case '\\' => buf(len + 1) = '\\'; len += 2
      case '\n' => buf(len + 1) = 'n'; len += 2
      case '\r' => buf(len + 1) = 'r'; len += 2
      case '\t' => buf(len + 1) = 't'; len += 2
      case '\b' => buf(len + 1) = 'b'; len += 2
      case '\f' => buf(len + 1) = 'f'; len += 2
      case _    =>
        buf(len + 1) = 'u'
        buf(len + 2) = '0'
        buf(len + 3) = '0'
        buf(len + 4) = JsonOut.Hex(b >> 4)
        buf(len + 5) = JsonOut.Hex(b & 0xf)
        len += 6

object JsonOut:
  private val Hex   = "0123456789abcdef".getBytes(UTF_8)
  private val True  = "true".getBytes(UTF_8)
  private val False = "false".getBytes(UTF_8)

  /** An ASCII constant as bytes, for [[JsonOut.raw]]. */
  def ascii(s: String): Array[Byte] = s.getBytes(UTF_8)
