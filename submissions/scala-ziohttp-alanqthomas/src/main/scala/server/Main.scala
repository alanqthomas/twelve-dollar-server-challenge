package server

import java.nio.charset.StandardCharsets.UTF_8

import zio.*
import zio.http.*
import zio.http.netty.{ChannelType, NettyConfig}
import zio.http.netty.NettyConfig.LeakDetectionLevel

object Main extends ZIOAppDefault:
  private def env(name: String): Task[String] =
    ZIO.attempt(sys.env.get(name)).someOrFail(new IllegalArgumentException(s"$name is not set"))

  /** Netty event-loop threads that serve requests (and run SQLite). One per vCPU; the box has one. */
  private val ioThreads = sys.props.get("server.ioThreads").map(_.toInt).getOrElse(1)

  /**
   * Netty transport. io_uring when the kernel allows it: it batches the read/write syscalls of many connections
   * into one `io_uring_enter`, which measured ~8% more capacity than epoll on one core. Else epoll, else NIO.
   */
  private val transport = sys.props.getOrElse("server.transport", "auto") match
    case "uring" => ChannelType.URING
    case "epoll" => ChannelType.EPOLL
    case "nio"   => ChannelType.NIO
    case _       =>
      if io.netty.channel.uring.IoUring.isAvailable then ChannelType.URING
      else if io.netty.channel.epoll.Epoll.isAvailable then ChannelType.EPOLL
      else ChannelType.NIO

  /**
   * SQLite settings (system properties, for experiments). No mmap by default: the database file grows as posts and
   * likes are written, and every growth past the mapped size makes SQLite remap the file and fault pages back in.
   */
  private val dbSettings = Db.Settings(
    mmapBytes = sys.props.get("server.mmapBytes").map(_.toLong).getOrElse(0L),
    cacheKiB = sys.props.get("server.cacheKiB").map(_.toInt).getOrElse(65536),
    checkpointEveryMillis = sys.props.get("server.checkpointMillis").map(_.toLong).getOrElse(1000L),
  )

  /** The writer thread lives as long as the server: started before it, stopped (after its last batch) after it. */
  private def writer(db: Db): URIO[Scope, Unit] =
    ZIO.acquireRelease(ZIO.succeed(db.writer.start()))(_ => ZIO.attemptBlocking(db.writer.stop()).orDie)

  override def run: ZIO[Any, Throwable, Unit] = ZIO.scoped:
    for
      path   <- env("SQLITE_PATH")
      secret <- env("JWT_SECRET")
      host   <- env("HOST").orElseSucceed("127.0.0.1")
      port   <- env("PORT").map(_.toInt).orElseSucceed(3000)
      db     <- ZIO.attempt(new Db(path, dbSettings))
      _      <- writer(db)
      api     = new Api(db, new Jwt(secret.getBytes(UTF_8)), java.lang.System.nanoTime())
      config  = Server.Config.default
                  .binding(host, port)
                  .keepAlive(true)                  // and no idle timeout: keep-alive connections stay open
                  .soBacklog(65535)                 // the kernel caps it at net.core.somaxconn
                  .disableRequestStreaming(64 * 1024)
                  .avoidContextSwitching(true)
                  .logWarningOnFatalError(false)
      netty   = NettyConfig.default
                  .maxThreads(ioThreads)
                  .channelType(transport)
                  .bossGroup(NettyConfig.default.bossGroup.copy(channelType = transport))
                  .leakDetection(LeakDetectionLevel.DISABLED)
      _      <- ZIO.logInfo(s"listening on $host:$port ($transport, $ioThreads event-loop thread(s)), database $path, " +
                  s"SQLite ${server.sqlite.Sqlite.version()}, $dbSettings")
      _      <- Server
                  .serve(api.routes)
                  .provide(ZLayer.succeed(config), ZLayer.succeed(netty), Server.customized)
    yield ()
