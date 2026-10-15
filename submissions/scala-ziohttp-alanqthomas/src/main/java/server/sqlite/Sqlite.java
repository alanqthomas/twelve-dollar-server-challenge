package server.sqlite;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A minimal FFM binding to the SQLite C API, for the library build.sh compiles from the official amalgamation.
 *
 * <p>Written in Java rather than Scala so every downcall handle is a {@code static final} field, which the JIT
 * treats as a constant and inlines. Pointers ({@code sqlite3*}, {@code sqlite3_stmt*}, column text) travel as raw
 * {@code long}s: on x86-64 Linux they use the same registers, and no {@code MemorySegment} is created per call.
 * Short calls that never block (bind, column, reset, changes) are critical downcalls, which skip the Java/native
 * thread-state transition; {@code sqlite3_step} and checkpoints can do I/O, so they are ordinary downcalls.
 */
public final class Sqlite {
  private Sqlite() {}

  public static final int OK = 0;
  public static final int ROW = 100;
  public static final int DONE = 101;

  public static final int OPEN_READWRITE = 0x00000002;
  public static final int OPEN_NOMUTEX = 0x00008000;
  public static final int OPEN_EXRESCODE = 0x02000000;
  public static final int PREPARE_PERSISTENT = 0x01;
  public static final int CHECKPOINT_PASSIVE = 0;

  /** SQLITE_STATIC: SQLite uses bound memory in place; it must stay valid until the statement is re-bound. */
  private static final long STATIC = 0L;

  /** All of native memory, so column text can be copied out without a MemorySegment per call. */
  private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIB =
      SymbolLookup.libraryLookup(Path.of(System.getProperty("sqlite.library", "target/libsqlite3.so")), Arena.global());
  private static final Linker.Option CRITICAL = Linker.Option.critical(false);

  private static MethodHandle fn(String name, FunctionDescriptor fd, Linker.Option... options) {
    MemorySegment symbol = LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("sqlite3: no symbol " + name));
    return LINKER.downcallHandle(symbol, fd, options);
  }

  private static final MethodHandle INITIALIZE = fn("sqlite3_initialize", FunctionDescriptor.of(JAVA_INT));
  private static final MethodHandle LIBVERSION = fn("sqlite3_libversion", FunctionDescriptor.of(JAVA_LONG));
  private static final MethodHandle OPEN_V2 =
      fn("sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG));
  private static final MethodHandle CLOSE_V2 = fn("sqlite3_close_v2", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
  private static final MethodHandle EXEC =
      fn("sqlite3_exec", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
  private static final MethodHandle PREPARE_V3 =
      fn("sqlite3_prepare_v3", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
  private static final MethodHandle STEP = fn("sqlite3_step", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
  private static final MethodHandle RESET = fn("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, JAVA_LONG), CRITICAL);
  private static final MethodHandle BIND_INT64 =
      fn("sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG), CRITICAL);
  private static final MethodHandle BIND_TEXT = fn("sqlite3_bind_text",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG), CRITICAL);
  private static final MethodHandle COLUMN_INT64 =
      fn("sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT), CRITICAL);
  private static final MethodHandle COLUMN_TEXT =
      fn("sqlite3_column_text", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT), CRITICAL);
  private static final MethodHandle COLUMN_BYTES =
      fn("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT), CRITICAL);
  private static final MethodHandle CHANGES = fn("sqlite3_changes", FunctionDescriptor.of(JAVA_INT, JAVA_LONG), CRITICAL);
  private static final MethodHandle ERRMSG = fn("sqlite3_errmsg", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
  private static final MethodHandle WAL_CHECKPOINT_V2 = fn("sqlite3_wal_checkpoint_v2",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));

  static {
    int rc;
    try {
      rc = (int) INITIALIZE.invokeExact();
    } catch (Throwable t) {
      throw failure(t);
    }
    if (rc != OK) throw new SqliteException(rc, "sqlite3_initialize failed");
  }

  private static RuntimeException failure(Throwable t) {
    if (t instanceof RuntimeException e) return e;
    if (t instanceof Error e) throw e;
    return new IllegalStateException(t);
  }

  private static String cString(long address) {
    return address == 0 ? "" : ALL.getString(address);
  }

  public static String version() {
    try {
      return cString((long) LIBVERSION.invokeExact());
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** Opens a connection and returns its {@code sqlite3*}. */
  public static long open(String path, int flags) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      int rc = (int) OPEN_V2.invokeExact(arena.allocateFrom(path), out, flags, 0L);
      long db = out.get(JAVA_LONG, 0);
      if (rc != OK) {
        String message = db == 0 ? "out of memory" : errmsg(db);
        if (db != 0) close(db);
        throw new SqliteException(rc, "cannot open " + path + ": " + message);
      }
      return db;
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  public static void close(long db) {
    try {
      int unused = (int) CLOSE_V2.invokeExact(db);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** Runs SQL that returns no rows we need (pragmas). */
  public static void exec(long db, String sql) {
    try (Arena arena = Arena.ofConfined()) {
      int rc = (int) EXEC.invokeExact(db, arena.allocateFrom(sql), 0L, 0L, 0L);
      if (rc != OK) throw new SqliteException(rc, errmsg(db) + " (" + sql + ")");
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** Compiles one statement for repeated use and returns its {@code sqlite3_stmt*}. */
  public static long prepare(long db, String sql) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      int rc = (int) PREPARE_V3.invokeExact(db, arena.allocateFrom(sql), -1, PREPARE_PERSISTENT, out, 0L);
      if (rc != OK) throw new SqliteException(rc, errmsg(db) + " (" + sql + ")");
      return out.get(JAVA_LONG, 0);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** {@link #ROW}, {@link #DONE} or an error code. */
  public static int step(long stmt) {
    try {
      return (int) STEP.invokeExact(stmt);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  public static void reset(long stmt) {
    try {
      int unused = (int) RESET.invokeExact(stmt);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  public static void bindLong(long stmt, int index, long value) {
    int rc;
    try {
      rc = (int) BIND_INT64.invokeExact(stmt, index, value);
    } catch (Throwable t) {
      throw failure(t);
    }
    if (rc != OK) throw new SqliteException(rc, "bind failed");
  }

  /** Binds {@code length} UTF-8 bytes at {@code address}, which must stay unchanged until the next bind. */
  public static void bindText(long stmt, int index, long address, int length) {
    int rc;
    try {
      rc = (int) BIND_TEXT.invokeExact(stmt, index, address, length, STATIC);
    } catch (Throwable t) {
      throw failure(t);
    }
    if (rc != OK) throw new SqliteException(rc, "bind failed");
  }

  public static long columnLong(long stmt, int column) {
    try {
      return (long) COLUMN_INT64.invokeExact(stmt, column);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** The address of a text column's UTF-8 bytes, valid until the next step or reset. Call before {@link #columnBytes}. */
  public static long columnText(long stmt, int column) {
    try {
      return (long) COLUMN_TEXT.invokeExact(stmt, column);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  public static int columnBytes(long stmt, int column) {
    try {
      return (int) COLUMN_BYTES.invokeExact(stmt, column);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** Copies {@code length} bytes of native memory into a Java array. */
  public static void copy(long address, byte[] dst, int dstOffset, int length) {
    MemorySegment.copy(ALL, JAVA_BYTE, address, dst, dstOffset, length);
  }

  /** Copies a Java array into native memory. */
  public static void copy(byte[] src, int srcOffset, long address, int length) {
    MemorySegment.copy(src, srcOffset, ALL, JAVA_BYTE, address, length);
  }

  public static int changes(long db) {
    try {
      return (int) CHANGES.invokeExact(db);
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  public static String errmsg(long db) {
    try {
      return cString((long) ERRMSG.invokeExact(db));
    } catch (Throwable t) {
      throw failure(t);
    }
  }

  /** Runs a WAL checkpoint and returns {frames in the WAL, frames checkpointed}. */
  public static int[] checkpoint(long db, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment log = arena.allocate(JAVA_INT);
      MemorySegment done = arena.allocate(JAVA_INT);
      int rc = (int) WAL_CHECKPOINT_V2.invokeExact(db, 0L, mode, log, done);
      if (rc != OK) throw new SqliteException(rc, errmsg(db) + " (wal_checkpoint)");
      return new int[] {log.get(JAVA_INT, 0), done.get(JAVA_INT, 0)};
    } catch (Throwable t) {
      throw failure(t);
    }
  }
}
