package server

import java.nio.charset.StandardCharsets.UTF_8

import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.NettyConfig.LeakDetectionLevel

object Main extends ZIOAppDefault:
  private def env(name: String): Task[String] =
    ZIO.attempt(sys.env.get(name)).someOrFail(new IllegalArgumentException(s"$name is not set"))

  /** Netty event-loop threads that serve requests (and run SQLite). One per vCPU; the box has one. */
  private val ioThreads = sys.props.get("server.ioThreads").map(_.toInt).getOrElse(1)

  /** Moves WAL checkpoints (and their fsyncs) off the event loop: one PASSIVE checkpoint per second. */
  private def checkpointer(path: String): RIO[Scope, Unit] =
    ZIO
      .acquireRelease(ZIO.attemptBlocking(Db.open(path, requestConnection = false)))(c => ZIO.succeed(c.close()))
      .flatMap(conn => ZIO.attemptBlocking(Db.checkpoint(conn)).ignoreLogged.repeat(Schedule.spaced(1.second)))
      .unit

  override def run: ZIO[Any, Throwable, Unit] =
    for
      path   <- env("SQLITE_PATH")
      secret <- env("JWT_SECRET")
      host   <- env("HOST").orElseSucceed("127.0.0.1")
      port   <- env("PORT").map(_.toInt).orElseSucceed(3000)
      api     = new Api(new Db(path), new Jwt(secret.getBytes(UTF_8)), java.lang.System.nanoTime())
      _      <- ZIO.scoped(checkpointer(path)).forkDaemon
      config  = Server.Config.default
                  .binding(host, port)
                  .keepAlive(true)                  // and no idle timeout: keep-alive connections stay open
                  .soBacklog(65535)                 // the kernel caps it at net.core.somaxconn
                  .disableRequestStreaming(64 * 1024)
                  .avoidContextSwitching(true)
                  .logWarningOnFatalError(false)
      netty   = NettyConfig.default
                  .maxThreads(ioThreads)
                  .leakDetection(LeakDetectionLevel.DISABLED)
      _      <- ZIO.logInfo(s"listening on $host:$port with $ioThreads event-loop thread(s), database $path")
      _      <- Server
                  .serve(api.routes)
                  .provide(ZLayer.succeed(config), ZLayer.succeed(netty), Server.customized)
    yield ()
