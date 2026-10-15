#!/usr/bin/env bash
# Builds the server as a normal user:
#   target/libsqlite3.so  SQLite, compiled from the official amalgamation (pinned by SHA-256) with SQLite's
#                         recommended compile-time options for an app whose connections each stay on one thread
#   target/server.jar     sbt downloads the pinned dependencies from Maven Central
set -euo pipefail
cd "$(dirname "$0")"

SQLITE_VERSION=3540000   # 3.54.0
SQLITE_URL="https://www.sqlite.org/2026/sqlite-autoconf-$SQLITE_VERSION.tar.gz"
SQLITE_SHA256=134ec0802dda5795816e25d25872d20b312cb3973438c49b30bc40b7705ea9ed
SQLITE_FLAGS=(
  -DSQLITE_THREADSAFE=2               # each connection is used by one thread at a time: no per-connection mutex
  -DSQLITE_DEFAULT_MEMSTATUS=0        # no global mutex and counters around every allocation
  -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1  # synchronous=NORMAL in WAL mode (the app sets it as well)
  -DSQLITE_DQS=0
  -DSQLITE_LIKE_DOESNT_MATCH_BLOBS
  -DSQLITE_MAX_EXPR_DEPTH=0
  -DSQLITE_OMIT_AUTOINIT              # the app calls sqlite3_initialize itself
  -DSQLITE_OMIT_DECLTYPE
  -DSQLITE_OMIT_DEPRECATED
  -DSQLITE_OMIT_LOAD_EXTENSION
  -DSQLITE_OMIT_PROGRESS_CALLBACK
  -DSQLITE_OMIT_SHARED_CACHE
  -DSQLITE_USE_ALLOCA
  -DHAVE_USLEEP
)

mkdir -p target/sqlite
if [ "$(cat target/sqlite/VERSION 2>/dev/null)" != "$SQLITE_VERSION" ]; then
  curl -fsSL --retry 5 --retry-all-errors -o target/sqlite/src.tar.gz "$SQLITE_URL"
  echo "$SQLITE_SHA256  target/sqlite/src.tar.gz" | sha256sum -c -
  tar -xzf target/sqlite/src.tar.gz -C target/sqlite --strip-components=1 \
    "sqlite-autoconf-$SQLITE_VERSION/sqlite3.c" "sqlite-autoconf-$SQLITE_VERSION/sqlite3.h"
  echo "$SQLITE_VERSION" > target/sqlite/VERSION
fi
build_id="$SQLITE_VERSION ${SQLITE_FLAGS[*]}"
if [ ! -f target/libsqlite3.so ] || [ "$(cat target/sqlite/BUILD 2>/dev/null)" != "$build_id" ]; then
  gcc -O2 -fPIC -shared -o target/libsqlite3.so "${SQLITE_FLAGS[@]}" target/sqlite/sqlite3.c -lpthread -lm
  echo "$build_id" > target/sqlite/BUILD
fi

exec /opt/temurin-27/bin/java -Xmx1g -Dsbt.ci=true -Dsbt.supershell=false \
  -jar /opt/sbt-2.0.10/sbt-launch.jar assembly
