package server

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import java.util.concurrent.locks.ReentrantLock

import io.netty.util.concurrent.FastThreadLocal
import org.sqlite.{SQLiteConfig, SQLiteOpenMode}
import org.sqlite.core.{Codes, CoreStatement, SafeStmtPtr}

/**
 * SQLite access. Every thread that serves requests (the Netty event loop) gets its own connection with its
 * own prepared statements and JSON buffer, so reads never contend. Writes go through one JVM-wide lock so
 * two connections never race for SQLite's write lock (no SQLITE_BUSY, no busy-wait sleeps).
 *
 * Every request runs its query against SQLite while it is being served (rule 5): statements are reused,
 * results never are.
 */
final class Db(path: String):
  private val sessions = new FastThreadLocal[Db.Session]:
    override def initialValue(): Db.Session = new Db.Session(Db.open(path, requestConnection = true))

  def session(): Db.Session = sessions.get()

object Db:
  enum Like:
    case Inserted, AlreadyLiked, NoSuchPost

  private val writeLock = new ReentrantLock()

  def open(path: String, requestConnection: Boolean): Connection =
    val cfg = new SQLiteConfig()
    cfg.setJournalMode(SQLiteConfig.JournalMode.WAL)
    cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL) // rule 6: WAL + NORMAL
    cfg.setBusyTimeout(5000)
    cfg.setTempStore(SQLiteConfig.TempStore.MEMORY)
    cfg.setCacheSize(-16384)                                 // 16 MiB page cache
    cfg.setOpenMode(SQLiteOpenMode.NOMUTEX)                  // each connection stays on one thread
    val conn = cfg.createConnection(s"jdbc:sqlite:$path")
    val st   = conn.createStatement()
    try
      st.execute("PRAGMA mmap_size = 1073741824") // read the database file through the OS page cache
      // Commits never checkpoint inline (that fsyncs on the event loop); the background checkpointer in
      // Main does it. The high limit is only a backstop should the checkpointer ever fall behind.
      if requestConnection then st.execute("PRAGMA wal_autocheckpoint = 20000")
    finally st.close()
    conn

  /** Copies committed WAL frames into the database file. Run off the event loop. */
  def checkpoint(conn: Connection): Unit =
    val st = conn.createStatement()
    try st.execute("PRAGMA wal_checkpoint(PASSIVE)")
    finally st.close()

  private inline def locked[A](inline body: A): A =
    writeLock.lock()
    try body
    finally writeLock.unlock()

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

  /** One thread's connection, statements and response buffer. */
  final class Session(conn: Connection):
    val out: JsonOut                      = new JsonOut(16 * 1024)
    private val ping: PreparedStatement   = conn.prepareStatement("SELECT 1")
    private val feed: SafeStmtPtr         = conn.prepareStatement(FeedSql).asInstanceOf[CoreStatement].pointer
    private val post: PreparedStatement   = conn.prepareStatement(PostSql)
    private val insertPost                = conn.prepareStatement(InsertPostSql)
    private val insertLike                = conn.prepareStatement(InsertLikeSql)
    private val postExists                = conn.prepareStatement(PostExistsSql)

    def selectOne(): Unit =
      val rs = ping.executeQuery()
      try rs.next(): Unit
      finally rs.close()

    /**
     * `{"posts":[...]}` for the 20 newest posts.
     *
     * The hottest query, so it skips the JDBC ResultSet layer and steps the statement through sqlite-jdbc's
     * public low-level API: one lock for the whole query instead of one per call, and no per-execution
     * column-name lookups. That is ~12% less time per feed than `executeQuery` + `getBytes`.
     */
    def feedJson(): Array[Byte] =
      out.reset().raw(PostsOpen)
      feed.safeRun[Unit, SQLException] { (db, stmt) =>
        db.reset(stmt)
        try
          var rc    = db.step(stmt)
          var first = true
          while rc == Codes.SQLITE_ROW do
            if !first then out.byte(',')
            out
              .raw(IdKey).long(db.column_long(stmt, 0))
              .raw(BodyKey).string(db.column_blob(stmt, 1))
              .raw(CreatedKey).string(db.column_blob(stmt, 2))
              .raw(AuthorKey).string(db.column_blob(stmt, 3))
              .raw(LikesKey).long(db.column_long(stmt, 4))
              .byte('}')
            first = false
            rc = db.step(stmt)
          if rc != Codes.SQLITE_DONE then throw new SQLException(s"feed query failed: SQLite code $rc")
        finally db.reset(stmt): Unit // ends the read transaction
      }
      out.raw(PostsClose).toByteArray

    /** `{"post":{...}}`, or null when there is no such post. */
    def postJson(id: Long): Array[Byte] | Null =
      post.setLong(1, id)
      val rs = post.executeQuery()
      try
        if !rs.next() then null
        else
          out.reset().raw(PostOpen)
          writePost(rs)
          out.byte('}').toByteArray
      finally rs.close()

    private def writePost(rs: ResultSet): Unit =
      out
        .raw(IdKey).long(rs.getLong(1))
        .raw(BodyKey).string(rs.getBytes(2))
        .raw(CreatedKey).string(rs.getBytes(3))
        .raw(AuthorKey).string(rs.getBytes(4))
        .raw(LikesKey).long(rs.getLong(5))
        .byte('}')

    /** Inserts a post and returns its `{"post":{...}}` once the row is committed. */
    def createPostJson(userId: Long, username: String, body: String): Array[Byte] =
      val (id, createdAt) = locked {
        insertPost.setLong(1, userId)
        insertPost.setString(2, body)
        val rs = insertPost.executeQuery()
        try
          if !rs.next() then throw new IllegalStateException("INSERT ... RETURNING returned no row")
          val row = (rs.getLong(1), rs.getBytes(2))
          // Step to SQLITE_DONE: that is when the autocommit transaction commits.
          while rs.next() do ()
          row
        finally rs.close()
      }
      out.reset().raw(PostOpen)
        .raw(IdKey).long(id)
        .raw(BodyKey).string(body)
        .raw(CreatedKey).string(createdAt)
        .raw(AuthorKey).string(username)
        .raw(NoLikes)
        .toByteArray

    def like(userId: Long, postId: Long): Like =
      val inserted = locked {
        insertLike.setLong(1, userId)
        insertLike.setLong(2, postId)
        insertLike.executeUpdate() == 1 // autocommit: committed when this returns
      }
      if inserted then Like.Inserted
      else
        postExists.setLong(1, postId)
        val rs = postExists.executeQuery()
        try if rs.next() then Like.AlreadyLiked else Like.NoSuchPost
        finally rs.close()
