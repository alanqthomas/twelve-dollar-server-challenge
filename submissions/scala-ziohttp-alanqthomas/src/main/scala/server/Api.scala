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
 * Every handler is a plain function `Request => Response`, so zio-http gets back an already-completed `Exit` and
 * writes the response straight from the Netty event loop: no fiber, no executor hop. SQLite calls run inline
 * on that thread; they are index lookups that take microseconds, and the box has a single vCPU anyway.
 */
final class Api(db: Db, jwt: Jwt, startedAtNanos: Long):
  import Api.*

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "health"                         -> Handler.fromFunction[Request](_ => health()),
    Method.GET / "feed"                           -> Handler.fromFunction[Request](_ => guarded(feed())),
    Method.GET / "posts" / string("id")           -> Handler.fromFunction[(String, Request)]((id, _) => guarded(getPost(id))),
    Method.POST / "posts"                         -> Handler.fromFunctionZIO[Request](createPost),
    Method.POST / "posts" / string("id") / "like" ->
      Handler.fromFunction[(String, Request)]((id, req) => guarded(like(id, req))),
    RoutePattern.any                              -> Handler.fromFunction[(Path, Request)](_ => NotFound),
  )

  private def health(): Response =
    try
      db.session().selectOne()
      val uptime = (java.lang.System.nanoTime() - startedAtNanos) / 1_000_000_000L
      json(Status.Ok, s"""{"status":"ok","db":"ok","uptime_s":$uptime}""".getBytes(UTF_8))
    catch
      case NonFatal(e) =>
        val body = new JsonOut(256).raw(JsonOut.ascii("""{"status":"degraded","db":"unreachable","error":"""))
          .string(String.valueOf(e.getMessage)).byte('}')
        json(Status.ServiceUnavailable, body.toByteArray)

  private def feed(): Response = json(Status.Ok, db.session().feedJson())

  private def getPost(rawId: String): Response =
    val id = Ids.parsePositive(rawId)
    if id == Ids.Invalid then InvalidPostId
    else if id == Ids.Overflow then PostNotFound
    else
      val body = db.session().postJson(id)
      if body == null then PostNotFound else json(Status.Ok, body)

  private def createPost(req: Request): UIO[Response] =
    jwt.authenticate(authorization(req)) match
      case Auth.User(userId, username) =>
        // An aggregated body is already in memory: `asArray` is a completed Exit, so this stays synchronous.
        req.body.asArray.foldCause(_ => MalformedJson, bytes => guarded(createPost(userId, username, bytes)))
      case failure                     => Exit.succeed(authError(failure))

  private def createPost(userId: Long, username: String, bytes: Array[Byte]): Response =
    Json.decoder.decodeJson(new String(bytes, UTF_8)) match
      case Left(_)     => MalformedJson
      case Right(json) =>
        val raw = json match
          case obj: Json.Obj => Jwt.field(obj, "body")
          case _             => None
        raw match
          case Some(Json.Str(s)) =>
            val body = trimJs(s)
            if body.isEmpty then BodyRequired
            else if body.length > 500 then BodyTooLong
            else Response(Status.Created, JsonHeaders, Body.fromArray(db.session().createPostJson(userId, username, body)))
          case _                 => BodyRequired

  private def like(rawId: String, req: Request): Response =
    jwt.authenticate(authorization(req)) match // auth before the id, per the spec
      case Auth.User(userId, _) =>
        val postId = Ids.parsePositive(rawId)
        if postId == Ids.Invalid then InvalidPostId
        else if postId == Ids.Overflow then PostNotFound
        else
          db.session().like(userId, postId) match
            case Db.Like.Inserted     => json(Status.Created, likeJson(postId, alreadyLiked = false))
            case Db.Like.AlreadyLiked => json(Status.Ok, likeJson(postId, alreadyLiked = true))
            case Db.Like.NoSuchPost   => PostNotFound
      case failure              => authError(failure)

  private def likeJson(postId: Long, alreadyLiked: Boolean): Array[Byte] =
    db.session().out.reset()
      .raw(LikedOpen).bool(alreadyLiked)
      .raw(PostIdKey).long(postId)
      .byte('}')
      .toByteArray

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
    c == ' ' || (c >= '\t' && c <= '\r') || c == ' ' || c == ' ' || (c >= ' ' && c <= ' ') ||
      c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

  def trimJs(s: String): String =
    var start = 0
    var end   = s.length
    while start < end && isJsSpace(s.charAt(start)) do start += 1
    while end > start && isJsSpace(s.charAt(end - 1)) do end -= 1
    s.substring(start, end)
