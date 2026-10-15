# Scala 3 + ZIO + zio-http

| | |
|---|---|
| Language | Scala 3.10.0 on Eclipse Temurin JDK 27 (`jdk-27+35`) |
| Framework | ZIO 2.1.27 and zio-http 3.11.6, on Netty 4.2.19.Final (io_uring transport, epoll fallback) |
| SQLite driver | none: SQLite 3.54.0, compiled by `build.sh` from the official amalgamation (pinned by SHA-256) and called through Java's FFM API (`java.lang.foreign`, in `src/main/java/server/sqlite`) |
| JSON | zio-json 1.1.0 parses request bodies and JWT parts; responses are written by a small UTF-8 writer |
| Build | sbt 2.0.10 with sbt-assembly 2.5.0: one fat jar, `target/server.jar`, plain JVM bytecode; gcc for SQLite |
| **Nginx or direct** | **Direct**: serves `0.0.0.0:80` itself |

Every dependency version is pinned: Scala libraries in `build.sbt`, the JDK and the sbt launcher in
`install.sh`, and SQLite in `build.sh`, all checked against their checksums.

## Running it

```bash
sudo bash install.sh   # Temurin JDK 27 -> /opt/temurin-27, sbt 2.0.10 launcher -> /opt/sbt-2.0.10, gcc
bash build.sh          # SQLite -> target/libsqlite3.so, then sbt assembly -> target/server.jar
SQLITE_PATH=... JWT_SECRET=... HOST=0.0.0.0 PORT=80 bash start.sh
```

## How a request is served

One Netty event-loop thread serves every request, because the box has one vCPU. It reads the request, runs
the handler and writes the response. Reads (`/feed`, `/posts/:id`, `/health`) query SQLite right there, on that
thread's own connection.

Those handlers are plain functions `Request => Response`. zio-http turns them into handlers that return an
already-completed `Exit`, and its Netty handler writes a completed `Exit` straight to the channel. So reads, and
every request that is rejected (bad token, bad id, bad body), never create a fiber or touch the ZIO scheduler.

Writes (`POST /posts`, `POST /posts/:id/like`) are validated on the event loop the same way, then handed to a
single writer thread with `ZIO.async`. The writer commits them in batches and completes each response once its
transaction has committed. ZIO runs the rest: the app lifecycle, configuration, the server layer, and the writer
thread as a scoped resource.

## Optimizations, and why

- **Zero-fiber request path.** See above. Building a fiber per request would cost far more than the routing
  does. With this path, zio-http's overhead (request object, routing, response encoding) is a few percent of the
  CPU in a profile.
- **SQLite through FFM, built for this app.** `build.sh` compiles the amalgamation with SQLite's recommended
  options for an app whose connections each stay on one thread: `SQLITE_THREADSAFE=2` (no per-connection mutex),
  `SQLITE_DEFAULT_MEMSTATUS=0` (no global mutex around every allocation), `OMIT_SHARED_CACHE`,
  `OMIT_DEPRECATED`, `DQS=0` and so on. `SQLITE_ENABLE_QPSG` keeps query plans independent of bound values:
  without it, binding a new id to the single-post statement made SQLite re-parse and re-plan it on every
  `GET /posts/:id` (11% of the event loop's CPU in a profile). The binding is a small Java class, so every downcall handle is a
  `static final` the JIT inlines. Pointers travel as raw `long`s, and the short calls (`bind`, `column`, `reset`,
  `changes`) are critical downcalls that skip the thread-state transition. Text columns are copied from SQLite's
  memory straight into the response buffer. Against sqlite-jdbc (whose glue was ~13% of the event loop's CPU in
  a profile), server CPU per request fell ~19% under the same load, QPSG included.
- **Group commit on one writer thread (rule 6).** Whatever writes are queued when the writer wakes up run in one
  `BEGIN IMMEDIATE … COMMIT`, and each caller hears back only after `COMMIT` returns, so a 201 always means a
  committed row. One writer also means writes never wait on SQLite's write lock, and the event loop never waits
  on a write.
- **Checkpoints between batches, on the writer.** Connections set `wal_autocheckpoint=0`, so no commit ever
  checkpoints, and the writer runs a PASSIVE checkpoint about once a second between batches. Nobody appends to
  the WAL while it runs, so it catches up completely and the next write restarts the log from the start. The
  previous version checkpointed from a separate thread, raced the writes and fell behind: on a DigitalOcean
  droplet its WAL reached 82 MB, the size where the safety-net autocheckpoint ran inside requests.
  `journal_size_limit` trims the file after a burst.
- **No mmap.** The database file grows as posts and likes are written, and every growth past the mapped size
  makes SQLite remap the file and fault pages back in. Under the same load, a 1 GiB `mmap_size` cost ~15% more
  CPU per request than reads through a 64 MiB page cache per connection.
- **The feed query.** It is the reference SQL. I benchmarked a correlated subquery for the username, a CTE, a
  derived table, and `LEFT JOIN ... GROUP BY`. None beat it, and the `GROUP BY` version was 2.5x slower.
  Building the whole JSON in SQLite with `json_group_array(json_object(...))` gives identical bytes, but through
  the FFM binding it was 15% slower for the feed (12.1 µs against 10.5 µs) and no faster for a single post: column
  reads cost nanoseconds here, and SQLite's JSON functions add a string buffer per object plus another copy.
- **io_uring.** Netty's io_uring transport batches the reads and writes of many connections into one
  `io_uring_enter`. On one core, under a many-connections load, it held ~8% more requests per second than epoll
  at saturation, with lower tail latency. If the kernel refuses io_uring, the server falls back to epoll, then NIO.
  The completion queue stays at Netty's 4,096 entries: on a droplet at 30,000 users, a 65,536-entry queue tripled
  the event loop's kernel time inside `io_uring_enter` and the server fell over, though it measured fine locally.
- **Quiet about clients that gave up.** Netty logs a stack trace for every response written to a connection the
  client already closed. Under overload that was thousands of lines a minute on the one core trying to catch up,
  so only severe pipeline errors are logged.
- **Direct, not Nginx.** On one vCPU, Nginx's CPU comes straight out of the app's budget. Nginx's
  `worker_connections 16384` also caps a run at about 16k users. Netty holds idle keep-alive connections cheaply,
  and there is no idle timeout, so connections stay open as long as the client wants.
- **JWT.** HS256 is verified in full on every request (rule 5): signature, `alg`, `exp`/`nbf`, then the
  `sub`/`username` claims. The signature is checked first, with a constant-time compare, before any JSON is
  parsed. The only thing reused is each thread's `Mac`, which holds the key schedule derived from the secret,
  not any token.
- **JVM.** Serial GC: one vCPU, so there are no GC threads to compete with. The heap is a fixed, pre-touched
  512 MiB. `-XX:MetaspaceSize=128m` sits above the ~35 MB of classes the app loads, so metaspace growth never
  triggers a full GC. Compact object headers make objects smaller and cache use better.
- **Netty and zio-http settings.** A listen backlog of 65535: zio-http's default is 100, too small for thousands
  of users connecting during the ramp-up, and the kernel caps it at `somaxconn` anyway. Leak detection is off and
  requests are aggregated up to 64 KiB.

## Measurements

**On a DigitalOcean `s-1vcpu-2gb` droplet** (Ubuntu 24.04, the previous version: sqlite-jdbc, no group commit),
set up by `install.sh`/`build.sh` and run as a systemd service with `LimitNOFILE=65535`, with `bench/load.js`
on five separate k6 droplets and the README's scoring procedure (500-user steps):

| Users | Result | p95 | p99 | Server CPU |
|---|---|---|---|---|
| 20,000 | pass | 9 ms | 29 ms | 47% |
| 30,000 | pass | 11 ms | 44 ms | 56% |
| 36,500 | **pass, 5-minute hold** | 24–105 ms | 97–286 ms | 64–71% |
| 37,000 | passed once, failed the 5-minute confirmation | 31–56 ms | 140 ms / 1.5 s | 66–67% |
| 40,000 | fail | 241 ms | 1.1 s | 76% |

No run had a failed request: every failure was p99. The droplet's vCPU is shared, and steal time spikes of
20–29% came every few minutes at every load. At ~70% busy, a spike left no headroom, requests queued for several
seconds, and p99 crossed 1 s. So the limit is CPU per request, which is what the SQLite changes above go after.

**Local A/B of this version** (Ryzen 9 9950X3D, server pinned to one core, k6 with 20,000 users at 4x speed,
~6,550 req/s, 0 errors in all three):

| Version | Server CPU per request | p99 | Largest WAL |
|---|---|---|---|
| Previous (sqlite-jdbc, background checkpointer) | 21 µs | 1.28 ms | 17 MB |
| **This one (FFM, group commit, QPSG, no mmap)** | **17 µs** | **0.72 ms** | **9 MB** |
| This one without QPSG | 19 µs | 0.99 ms | 9 MB |
| This one without QPSG, with a 1 GiB `mmap_size` | 22 µs | 1.71 ms | 9 MB |

## License

MIT, under the repo's [license](../../LICENSE).
