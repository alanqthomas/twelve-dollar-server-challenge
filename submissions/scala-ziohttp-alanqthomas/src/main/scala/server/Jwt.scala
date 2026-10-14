package server

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import io.netty.util.concurrent.FastThreadLocal
import zio.json.ast.Json

/** The outcome of checking an `Authorization` header. */
enum Auth:
  case User(id: Long, username: String)
  case MissingBearer, InvalidToken, InvalidPayload

/**
 * HS256 JWT verification, done in full on every request (rule 5: no remembered verifications).
 *
 * Only the `Mac` object is reused: it holds the HMAC key schedule (derived from the secret), not any token.
 */
final class Jwt(secret: Array[Byte]):
  private val macs = new FastThreadLocal[Mac]:
    override def initialValue(): Mac =
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(new SecretKeySpec(secret, "HmacSHA256"))
      mac

  private val b64     = Base64.getUrlDecoder
  private val b64Out  = Base64.getUrlEncoder.withoutPadding
  private val TokenAt = "Bearer ".length

  def authenticate(authorization: String | Null): Auth =
    if authorization == null || !authorization.startsWith("Bearer ") then Auth.MissingBearer
    else
      try verify(authorization)
      catch case _: IllegalArgumentException => Auth.InvalidToken // bad base64

  private def verify(header: String): Auth =
    val dot1 = header.indexOf('.', TokenAt)
    val dot2 = if dot1 < 0 then -1 else header.indexOf('.', dot1 + 1)
    if dot2 < 0 || header.indexOf('.', dot2 + 1) >= 0 then return Auth.InvalidToken

    // Signature first: it is cheap and keeps unauthenticated JSON away from the parser.
    // ISO_8859_1 maps each char to one byte; a non-ASCII token then fails the signature check.
    val mac      = macs.get()
    mac.update(header.substring(TokenAt, dot2).getBytes(ISO_8859_1))
    val expected = b64Out.encode(mac.doFinal())
    if !MessageDigest.isEqual(expected, header.substring(dot2 + 1).getBytes(ISO_8859_1)) then
      return Auth.InvalidToken

    decodeObject(header.substring(TokenAt, dot1)) match
      case Some(h) if Jwt.field(h, "alg").contains(Json.Str("HS256")) => ()
      case _                                                          => return Auth.InvalidToken

    decodeObject(header.substring(dot1 + 1, dot2)) match
      case None          => Auth.InvalidToken
      case Some(payload) =>
        val now = System.currentTimeMillis() / 1000
        if !Jwt.timeOk(Jwt.field(payload, "exp"), n => now < n) || !Jwt.timeOk(Jwt.field(payload, "nbf"), n => now >= n)
        then Auth.InvalidToken
        else
          (Jwt.field(payload, "sub"), Jwt.field(payload, "username")) match
            case (Some(Json.Str(sub)), Some(Json.Str(username))) =>
              val id = Ids.parsePositive(sub)
              if id > 0 then Auth.User(id, username) else Auth.InvalidPayload
            case _                                               => Auth.InvalidPayload

  private def decodeObject(part: String): Option[Json.Obj] =
    Json.decoder.decodeJson(new String(b64.decode(part), UTF_8)) match
      case Right(obj: Json.Obj) => Some(obj)
      case _                    => None

object Jwt:
  /** The last value for `key`, like `JSON.parse` keeps the last of duplicate keys. */
  def field(obj: Json.Obj, key: String): Option[Json] =
    var i              = obj.fields.length - 1
    var found: Option[Json] = None
    while found.isEmpty && i >= 0 do
      val (k, v) = obj.fields(i)
      if k == key then found = Some(v)
      i -= 1
    found

  /** An absent claim passes; a present one must be a number that satisfies `ok`. */
  private def timeOk(claim: Option[Json], ok: Double => Boolean): Boolean = claim match
    case None              => true
    case Some(Json.Num(n)) => ok(n.doubleValue)
    case Some(_)           => false
