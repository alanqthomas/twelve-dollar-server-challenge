package server

object Ids:
  val Invalid  = -1L
  val Overflow = -2L

  /**
   * Parses a positive integer written in plain ASCII digits (no sign, decimal point or exponent).
   * Returns the value, [[Invalid]] for anything else (including 0), or [[Overflow]] past `Long.MaxValue`.
   */
  def parsePositive(s: String): Long =
    val n = s.length
    if n == 0 then return Invalid
    var v = 0L
    var i = 0
    while i < n do
      val d = s.charAt(i) - '0'
      if d < 0 || d > 9 then return Invalid
      if v > (Long.MaxValue - d) / 10 then
        // Too big for a Long; still reject later non-digits as invalid.
        while i < n do
          val c = s.charAt(i)
          if c < '0' || c > '9' then return Invalid
          i += 1
        return Overflow
      v = v * 10 + d
      i += 1
    if v == 0 then Invalid else v
