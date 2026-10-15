package server.sqlite;

/** A SQLite call returned an error code. */
public final class SqliteException extends RuntimeException {
  public final int code;

  public SqliteException(int code, String message) {
    super(message + " [sqlite code " + code + "]");
    this.code = code;
  }
}
