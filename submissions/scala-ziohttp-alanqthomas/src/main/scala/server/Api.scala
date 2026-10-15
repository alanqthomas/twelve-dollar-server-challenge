package server

import java.nio.charset.StandardCharsets.UTF_8

import scala.util.control.NonFatal

import io.netty.handler.codec.http.HttpHeaderNames
import zio.*
import zio.http.*
import zio.http.codec.PathCodec.string
import zio.json.ast.Json

/**
 * The five endpoints.
 *
 * Reads (`/health`, `/feed`, `/posts/:id`) and every rejected request are plain functions `Request => Response`:
 * zio-http gets back an already-completed `Exit` and writes the response straight from the Netty event loop, with
 * no fiber and no executor hop. SQLite reads run inline on that thread; they take microseconds, and the box has a
 * single vCPU anyway.
 *
 * Accepted writes are validated on the event loop too, then handed to the [[Writer]] thread with `ZIO.async`: the
 * response is completed from the writer once the write's transaction has committed.
 */
final class Api(db: Db, jwt: Jwt, startedAtNanos: Long):
  import Api.*

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "health"                         -> Handler.fromFunction[Request](_ => health()),
    Method.GET / "feed"                           -> Handler.fromFunction[Request](_ => guarded(feed())),
    Method.GET / "posts" / string("id")           -> Handler.fromFunction[(String, Request)]((id, _) => guarded(getPost(id))),
    Method.POST / "posts"                         -> Handler.fromFunctionZIO[Request](createPost),
    Method.POST / "posts" / string("id") / "like" -> Handler.fromFunctionZIO[(String, Request)]((id, req) => like(id, req)),
    RoutePattern.any                              -> Handler.fromFunction[(Path, Request)](_ => NotFound),
  )

  private def health(): Response =
    try
      db.reader().selectOne()
      val uptime = (java.lang.System.nanoTime() - startedAtNanos) / 1_000_000_000L
      json(Status.Ok, s"""{"status":"ok","db":"ok","uptime_s":$uptime}""".getBytes(UTF_8))
    catch
      case NonFatal(e) =>
        val body = new JsonOut(256).raw(JsonOut.ascii("""{"status":"degraded","db":"unreachable","error":"""))
          .string(String.valueOf(e.getMessage)).byte('}')
        json(Status.ServiceUnavailable, body.toByteArray)

  private def feed(): Response = json(Status.Ok, db.reader().feedJson())

  private def getPost(rawId: String): Response =
    val id = Ids.parsePositive(rawId)
    if id == Ids.Invalid then InvalidPostId
    else if id == Ids.Overflow then PostNotFound
    else
      val body = db.reader().postJson(id)
      if body == null then PostNotFound else json(Status.Ok, body)

  private def createPost(req: Request): UIO[Response] =
    try
      jwt.authenticate(authorization(req)) match
        case Auth.User(userId, username) =>
          // An aggregated body is already in memory: `asArray` is a completed Exit, so this stays synchronous.
          req.body.asArray.foldCauseZIO(_ => Exit.succeed(MalformedJson), bytes => createPost(userId, username, bytes))
        case failure                     => Exit.succeed(authError(failure))
    catch case NonFatal(_) => Exit.succeed(Internal)

  private def createPost(userId: Long, username: String, bytes: Array[Byte]): UIO[Response] =
    Json.decoder.decodeJson(new String(bytes, UTF_8)) match
      case Left(_)     => Exit.succeed(MalformedJson)
      case Right(json) =>
        val raw = json match
          case obj: Json.Obj => Jwt.field(obj, "body")
          case _             => None
        raw match
          case Some(Json.Str(s)) =>
            val body = trimJs(s)
            if body.isEmpty then Exit.succeed(BodyRequired)
            else if body.length > 500 then Exit.succeed(BodyTooLong)
            else
              ZIO.async[Any, Nothing, Response] { reply =>
                db.writer.createPost(userId, body, result => reply(Exit.succeed(created(result, body, username))))
              }
          case _                 => Exit.succeed(BodyRequired)

  private def like(rawId: String, req: Request): UIO[Response] =
    try
      jwt.authenticate(authorization(req)) match // auth before the id, per the spec
        case Auth.User(userId, _) =>
          val postId = Ids.parsePositive(rawId)
          if postId == Ids.Invalid then Exit.succeed(InvalidPostId)
          else if postId == Ids.Overflow then Exit.succeed(PostNotFound)
          else
            ZIO.async[Any, Nothing, Response] { reply =>
              db.writer.like(userId, postId, result => reply(Exit.succeed(liked(result, postId))))
            }
        case failure              => Exit.succeed(authError(failure))
    catch case NonFatal(_) => Exit.succeed(Internal)

object Api:
  private val JsonHeaders = Headers(Header.Custom("content-type", "application/json"))

  def json(status: Status, body: Array[Byte]): Response = Response(status, JsonHeaders, Body.fromArray(body))

  private def errorBody(message: String) = s"""{"error":"$message"}""".getBytes(UTF_8)

  private val NotFoundBody      = errorBody("not found")
  private val InvalidIdBody     = errorBody("invalid post id")
  private val PostNotFoundBody  = errorBody("post not found")
  private val MissingBearerBody = errorBody("missing bearer token")
  private val InvalidTokenBody  = errorBody("invalid or expired token")
  private val BadPayloadBody    = errorBody("invalid token payload")
  private val MalformedBody     = errorBody("malformed JSON body")
  private val RequiredBody      = errorBody("body is required")
  private val TooLongBody       = errorBody("body must be at most 500 characters")
  private val InternalBody      = errorBody("internal server error")

  // Fresh Response objects each time: zio-http memoizes a Response's encoding (Date header included).
  private def NotFound      = json(Status.NotFound, NotFoundBody)
  private def InvalidPostId = json(Status.BadRequest, InvalidIdBody)
  private def PostNotFound  = json(Status.NotFound, PostNotFoundBody)
  private def MalformedJson = json(Status.BadRequest, MalformedBody)
  private def BodyRequired  = json(Status.BadRequest, RequiredBody)
  private def BodyTooLong   = json(Status.BadRequest, TooLongBody)
  private def Internal      = json(Status.InternalServerError, InternalBody)

  private val LikedOpen = JsonOut.ascii("""{"liked":true,"already_liked":""")
  private val PostIdKey = JsonOut.ascii(""","post_id":""")

  private def created(result: WriteResult, body: String, username: String): Response = result match
    case WriteResult.PostCreated(id, createdAt) =>
      json(Status.Created, Db.createdPostJson(id, body, createdAt, username))
    case _                                      => Internal

  private def liked(result: WriteResult, postId: Long): Response = result match
    case WriteResult.Liked(already) =>
      val body = new JsonOut(64).raw(LikedOpen).bool(already).raw(PostIdKey).long(postId).byte('}').toByteArray
      json(if already then Status.Ok else Status.Created, body)
    case WriteResult.NoSuchPost     => PostNotFound
    case _                          => Internal

  private def authorization(req: Request): String | Null =
    req.headers.rawHeaders(HttpHeaderNames.AUTHORIZATION) match
      case values if values.isEmpty => null
      case values                   => values.head

  private def authError(failure: Auth): Response = failure match
    case Auth.MissingBearer  => json(Status.Unauthorized, MissingBearerBody)
    case Auth.InvalidPayload => json(Status.Unauthorized, BadPayloadBody)
    case _                   => json(Status.Unauthorized, InvalidTokenBody)

  /** Any unexpected failure becomes the spec's 500 instead of zio-http's default error page. */
  private inline def guarded(inline response: Response): Response =
    try response
    catch case NonFatal(_) => Internal

  /** `String.prototype.trim()`: JavaScript's WhiteSpace and LineTerminator characters. */
  private def isJsSpace(c: Char): Boolean =
    // Escapes, not literal characters: editors strip U+2028/U+2029 as "unusual line terminators".
    c == ' ' || (c >= '\t' && c <= '\r') || c == '\u00a0' || c == '\u1680' || (c >= '\u2000' && c <= '\u200a') ||
      c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff'

  def trimJs(s: String): String =
    var start = 0
    var end   = s.length
    while start < end && isJsSpace(s.charAt(start)) do start += 1
    while end > start && isJsSpace(s.charAt(end - 1)) do end -= 1
    s.substring(start, end)
