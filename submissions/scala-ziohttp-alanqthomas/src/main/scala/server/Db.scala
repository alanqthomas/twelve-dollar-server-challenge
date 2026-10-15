package server

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import scala.util.control.NonFatal

import io.netty.util.concurrent.FastThreadLocal
import server.sqlite.{Sqlite, SqliteException}

/**
 * SQLite, through the FFM binding in [[server.sqlite.Sqlite]] (a library compiled by build.sh).
 *
 * Reads run inline on the thread serving the request: every Netty event-loop thread gets its own connection,
 * statements and JSON buffer, so reads never contend. Writes go to the single [[Writer]] thread, which commits
 * them in batches. Every request reads its data from SQLite while it is being served (rule 5): statements are
 * reused, results never are.
 */
final class Db(path: String, settings: Db.Settings):
  private val readers = new FastThreadLocal[Db.Reader]:
    override def initialValue(): Db.Reader = new Db.Reader(Db.open(path, settings))

  def reader(): Db.Reader = readers.get()

  val writer: Writer = new Writer(Db.open(path, settings), settings)

object Db:
  /** Connection settings; mmap and the page cache are per connection. */
  final case class Settings(mmapBytes: Long, cacheKiB: Int, checkpointEveryMillis: Long, batchWindowMillis: Long)

  def open(path: String, settings: Settings): Long =
    val db = Sqlite.open(path, Sqlite.OPEN_READWRITE | Sqlite.OPEN_NOMUTEX | Sqlite.OPEN_EXRESCODE)
    Sqlite.exec(db, "PRAGMA journal_mode = WAL")
    Sqlite.exec(db, "PRAGMA synchronous = NORMAL") // rule 6: WAL + NORMAL
    Sqlite.exec(db, "PRAGMA busy_timeout = 5000")
    Sqlite.exec(db, "PRAGMA temp_store = MEMORY")
    Sqlite.exec(db, s"PRAGMA cache_size = -${settings.cacheKiB}")
    Sqlite.exec(db, s"PRAGMA mmap_size = ${settings.mmapBytes}")
    // Commits never checkpoint: the writer does it between batches (see Writer).
    Sqlite.exec(db, "PRAGMA wal_autocheckpoint = 0")
    // Once a checkpoint lets the WAL restart, truncate a file that grew during a burst.
    Sqlite.exec(db, "PRAGMA journal_size_limit = 16777216")
    db

  private val PostColumns =
    """SELECT p.id, p.body, p.created_at, u.username,
      |       (SELECT count(*) FROM likes l WHERE l.post_id = p.id)
      |  FROM posts p JOIN users u ON u.id = p.user_id""".stripMargin

  val FeedSql: String       = PostColumns + " ORDER BY p.created_at DESC, p.id DESC LIMIT 20"
  val PostSql: String       = PostColumns + " WHERE p.id = ?"
  val InsertPostSql: String = "INSERT INTO posts (user_id, body) VALUES (?, ?) RETURNING id, created_at"
  // Inserts only when the post exists; 0 changes means "already liked" or "no such post".
  val InsertLikeSql: String =
    """INSERT INTO likes (user_id, post_id)
      |SELECT ?1, ?2 WHERE EXISTS (SELECT 1 FROM posts WHERE id = ?2)
      |    ON CONFLICT (user_id, post_id) DO NOTHING""".stripMargin
  val PostExistsSql: String = "SELECT 1 FROM posts WHERE id = ?"

  private val PostsOpen  = JsonOut.ascii("""{"posts":[""")
  private val PostsClose = JsonOut.ascii("]}")
  private val PostOpen   = JsonOut.ascii("""{"post":""")
  private val IdKey      = JsonOut.ascii("""{"id":""")
  private val BodyKey    = JsonOut.ascii(""","body":""")
  private val CreatedKey = JsonOut.ascii(""","created_at":""")
  private val AuthorKey  = JsonOut.ascii(""","author":""")
  private val LikesKey   = JsonOut.ascii(""","like_count":""")
  private val NoLikes    = JsonOut.ascii(""","like_count":0}}""")

  /** Steps a statement that returns no rows, then resets it. */
  private[server] def run(db: Long, stmt: Long): Unit =
    val rc = Sqlite.step(stmt)
    Sqlite.reset(stmt)
    if rc != Sqlite.DONE then throw new SqliteException(rc, Sqlite.errmsg(db))

  /** One event-loop thread's connection, statements and response buffer. */
  final class Reader(db: Long):
    val out: JsonOut     = new JsonOut(16 * 1024)
    private val ping     = Sqlite.prepare(db, "SELECT 1")
    private val feed     = Sqlite.prepare(db, FeedSql)
    private val post     = Sqlite.prepare(db, PostSql)

    def selectOne(): Unit =
      try
        val rc = Sqlite.step(ping)
        if rc != Sqlite.ROW then throw new SqliteException(rc, Sqlite.errmsg(db))
      finally Sqlite.reset(ping)

    /** `{"posts":[...]}` for the 20 newest posts. */
    def feedJson(): Array[Byte] =
      out.reset().raw(PostsOpen)
      try
        var rc    = Sqlite.step(feed)
        var first = true
        while rc == Sqlite.ROW do
          if !first then out.byte(',')
          writePost(feed)
          first = false
          rc = Sqlite.step(feed)
        if rc != Sqlite.DONE then throw new SqliteException(rc, Sqlite.errmsg(db))
      finally Sqlite.reset(feed) // ends the read transaction
      out.raw(PostsClose).toByteArray

    /** `{"post":{...}}`, or null when there is no such post. */
    def postJson(id: Long): Array[Byte] | Null =
      Sqlite.bindLong(post, 1, id)
      try
        val rc = Sqlite.step(post)
        if rc == Sqlite.ROW then
          out.reset().raw(PostOpen)
          writePost(post)
          out.byte('}').toByteArray
        else if rc == Sqlite.DONE then null
        else throw new SqliteException(rc, Sqlite.errmsg(db))
      finally Sqlite.reset(post)

    private def writePost(stmt: Long): Unit =
      out.raw(IdKey).long(Sqlite.columnLong(stmt, 0))
      out.raw(BodyKey)
      text(stmt, 1)
      out.raw(CreatedKey)
      text(stmt, 2)
      out.raw(AuthorKey)
      text(stmt, 3)
      out.raw(LikesKey).long(Sqlite.columnLong(stmt, 4)).byte('}')

    private def text(stmt: Long, column: Int): Unit =
      val address = Sqlite.columnText(stmt, column) // before columnBytes, as SQLite recommends
      out.nativeString(address, Sqlite.columnBytes(stmt, column))

  /** `{"post":{...}}` for a post that was just created (it has no likes yet). */
  def createdPostJson(id: Long, body: String, createdAt: Array[Byte], username: String): Array[Byte] =
    new JsonOut(1024).raw(PostOpen)
      .raw(IdKey).long(id)
      .raw(BodyKey).string(body.getBytes(UTF_8))
      .raw(CreatedKey).string(createdAt)
      .raw(AuthorKey).string(username)
      .raw(NoLikes)
      .toByteArray

/** What a write did. Results are delivered only after the transaction holding the write has committed. */
enum WriteResult:
  case PostCreated(id: Long, createdAt: Array[Byte])
  case Liked(alreadyLiked: Boolean)
  case NoSuchPost
  case Failed(error: Throwable)

/**
 * The only thread that writes (group commit, allowed by rule 6). Whatever writes are queued when it wakes up run
 * in one `BEGIN IMMEDIATE … COMMIT`, and each caller hears back only after `COMMIT` returns: a 201 always means a
 * committed row. One writer also means writes never wait on SQLite's write lock.
 *
 * Checkpoints run here too, between batches, so they never stall a request and the WAL stays small: while this
 * thread checkpoints, nobody appends to the WAL, so the checkpoint can catch up with it completely and the next
 * write restarts the log from the beginning. (A checkpointer on its own thread races the writes and can fall
 * behind until the WAL is tens of MB.)
 */
final class Writer(db: Long, settings: Db.Settings):
  import Writer.*

  private val queue = new LinkedBlockingQueue[Op]()

  /** Queues a new post; `done` runs on the writer thread once it is committed (or failed). */
  def createPost(userId: Long, body: String, done: WriteResult => Unit): Unit =
    queue.add(new CreatePost(userId, body.getBytes(UTF_8), done))

  /** Queues a like; `done` runs on the writer thread once it is committed (or failed). */
  def like(userId: Long, postId: Long, done: WriteResult => Unit): Unit =
    queue.add(new Like(userId, postId, done))

  private val begin      = Sqlite.prepare(db, "BEGIN IMMEDIATE")
  private val commit     = Sqlite.prepare(db, "COMMIT")
  private val rollback   = Sqlite.prepare(db, "ROLLBACK")
  private val insertPost = Sqlite.prepare(db, Db.InsertPostSql)
  private val insertLike = Sqlite.prepare(db, Db.InsertLikeSql)
  private val postExists = Sqlite.prepare(db, Db.PostExistsSql)
  /** Native memory a post body is copied into for binding (SQLITE_STATIC: it must outlive the statement's step). */
  private val bodyBuffer = java.lang.foreign.Arena.global().allocate(MaxBodyBytes.toLong)

  @volatile private var running = true
  private val thread            = Thread.ofPlatform().name("sqlite-writer").daemon(true).unstarted(() => loop())

  def start(): Unit = thread.start()

  def stop(): Unit =
    running = false
    thread.interrupt()
    thread.join(5000)

  private def loop(): Unit =
    val batch          = new java.util.ArrayList[Op](MaxBatch)
    val results        = new Array[WriteResult](MaxBatch)
    var lastCheckpoint = System.nanoTime()
    while running do
      try
        val first = queue.poll(250, TimeUnit.MILLISECONDS)
        if first != null then
          batch.add(first)
          // Let more writes join this commit: every commit by this connection makes the readers drop their page
          // caches at their next read, so fewer, larger commits keep those caches warm.
          if settings.batchWindowMillis > 0 then
            java.util.concurrent.locks.LockSupport.parkNanos(settings.batchWindowMillis * 1_000_000L)
          queue.drainTo(batch, MaxBatch - 1)
          commitBatch(batch, results)
          batch.clear()
        if System.nanoTime() - lastCheckpoint >= settings.checkpointEveryMillis * 1_000_000L then
          checkpoint()
          lastCheckpoint = System.nanoTime()
      catch
        case _: InterruptedException => ()
        case NonFatal(e)             => System.err.println(s"sqlite-writer: ${e.getMessage}")
    var op = queue.poll()
    while op != null do // shutting down: anything left gets an error instead of waiting forever
      op.done(WriteResult.Failed(new IllegalStateException("shutting down")))
      op = queue.poll()

  private def commitBatch(batch: java.util.ArrayList[Op], results: Array[WriteResult]): Unit =
    val n = batch.size
    try
      Db.run(db, begin)
      var i = 0
      while i < n do
        results(i) =
          try execute(batch.get(i))
          catch case NonFatal(e) => WriteResult.Failed(e)
        i += 1
      Db.run(db, commit)
    catch
      case NonFatal(e) => // nothing in this batch is committed
        try Db.run(db, rollback)
        catch case NonFatal(_) => ()
        java.util.Arrays.fill(results.asInstanceOf[Array[AnyRef]], 0, n, WriteResult.Failed(e))
    var i = 0
    while i < n do
      val result = results(i)
      results(i) = null
      try batch.get(i).done(result)
      catch case NonFatal(e) => System.err.println(s"sqlite-writer: callback failed: ${e.getMessage}")
      i += 1

  private def execute(op: Op): WriteResult = op match
    case p: CreatePost =>
      if p.body.length > MaxBodyBytes then throw new IllegalArgumentException("body too large")
      Sqlite.copy(p.body, 0, bodyBuffer.address(), p.body.length)
      Sqlite.bindLong(insertPost, 1, p.userId)
      Sqlite.bindText(insertPost, 2, bodyBuffer.address(), p.body.length)
      try
        val rc = Sqlite.step(insertPost)
        if rc != Sqlite.ROW then throw new SqliteException(rc, Sqlite.errmsg(db))
        val id        = Sqlite.columnLong(insertPost, 0)
        val address   = Sqlite.columnText(insertPost, 1)
        val createdAt = new Array[Byte](Sqlite.columnBytes(insertPost, 1))
        Sqlite.copy(address, createdAt, 0, createdAt.length)
        val end = Sqlite.step(insertPost) // RETURNING: the insert finishes on the step after its row
        if end != Sqlite.DONE then throw new SqliteException(end, Sqlite.errmsg(db))
        WriteResult.PostCreated(id, createdAt)
      finally Sqlite.reset(insertPost)

    case l: Like =>
      Sqlite.bindLong(insertLike, 1, l.userId)
      Sqlite.bindLong(insertLike, 2, l.postId)
      Db.run(db, insertLike)
      if Sqlite.changes(db) == 1 then WriteResult.Liked(alreadyLiked = false)
      else
        Sqlite.bindLong(postExists, 1, l.postId)
        try
          Sqlite.step(postExists) match
            case Sqlite.ROW  => WriteResult.Liked(alreadyLiked = true)
            case Sqlite.DONE => WriteResult.NoSuchPost
            case rc          => throw new SqliteException(rc, Sqlite.errmsg(db))
        finally Sqlite.reset(postExists)

  private var lastWarning = 0L

  private def checkpoint(): Unit =
    val Array(walFrames, checkpointed) = Sqlite.checkpoint(db, Sqlite.CHECKPOINT_PASSIVE)
    // A WAL that keeps growing means checkpoints can't catch up (readers holding old snapshots): say so, rarely.
    if walFrames > LargeWalFrames && System.nanoTime() - lastWarning > 30_000_000_000L then
      lastWarning = System.nanoTime()
      System.err.println(s"sqlite-writer: WAL at $walFrames frames, $checkpointed checkpointed")

object Writer:
  private val MaxBatch       = 1024
  private val MaxBodyBytes   = 64 * 1024
  private val LargeWalFrames = 10_000

  private sealed abstract class Op(val done: WriteResult => Unit)
  private final class CreatePost(val userId: Long, val body: Array[Byte], done: WriteResult => Unit) extends Op(done)
  private final class Like(val userId: Long, val postId: Long, done: WriteResult => Unit)            extends Op(done)
