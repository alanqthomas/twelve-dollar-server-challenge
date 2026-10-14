# Scala 3 + ZIO + zio-http

| | |
|---|---|
| Language | Scala 3.10.0 on Eclipse Temurin JDK 27 (`jdk-27+35`) |
| Framework | ZIO 2.1.27 and zio-http 3.11.6, on Netty 4.2.19.Final (io_uring transport, epoll fallback) |
| SQLite driver | sqlite-jdbc 3.53.4.0 (bundles SQLite 3.53.4) |
| JSON | zio-json 1.1.0 parses request bodies and JWT parts; responses are written by a small UTF-8 writer |
| Build | sbt 2.0.10 with sbt-assembly 2.5.0: one fat jar, `target/server.jar`, plain JVM bytecode |
| **Nginx or direct** | **Direct**: serves `0.0.0.0:80` itself |

Every dependency version is pinned in `build.sbt`. `install.sh` pins the JDK and the sbt launcher and
checks their checksums.

## Running it

```bash
sudo bash install.sh   # Temurin JDK 27 -> /opt/temurin-27, sbt 2.0.10 launcher -> /opt/sbt-2.0.10
bash build.sh          # sbt assembly -> target/server.jar
SQLITE_PATH=... JWT_SECRET=... HOST=0.0.0.0 PORT=80 bash start.sh
```

## How a request is served

One Netty event-loop thread does all the work, because the box has one vCPU. It reads the request,
runs the handler (SQLite included) and writes the response, with no hand-off to another thread.

The five handlers are plain functions `Request => Response`. zio-http turns those into handlers that
return an already-completed `Exit`, and its Netty handler writes a completed `Exit` straight to the
channel. So the hot path never creates a fiber or touches the ZIO scheduler. ZIO runs everything else:
the app lifecycle, configuration, the server layer, and the background WAL checkpointer, which is a
scheduled fiber on ZIO's blocking pool.

## Optimizations, and why

- **Zero-fiber request path.** See above. Building a fiber per request would cost far more than the
  routing does. With this path, zio-http's overhead (request object, routing, response encoding) is a few
  percent of the CPU in a profile. `/health` serves ~100k req/s on one core.
- **io_uring.** Netty's io_uring transport batches the reads and writes of many connections into one
  `io_uring_enter`. On one core, in the realistic many-connections load below, it held ~8% more requests
  per second than epoll at saturation, with lower tail latency. If the kernel refuses io_uring, the server
  falls back to epoll, then NIO.
- **Direct, not Nginx.** On one vCPU, Nginx's CPU comes straight out of the app's budget. Nginx's
  `worker_connections 16384` also caps a run at about 16k users. Netty holds idle keep-alive connections
  cheaply: with 19,000 open connections the live heap was ~55 MB, about 1.8 KB per connection. There is
  no idle timeout, so connections stay open as long as the client wants.
- **SQLite, one connection per thread.** Each serving thread gets its own connection, prepared
  statements and response buffer through a `FastThreadLocal`, so reads never contend. All writes take
  one JVM-wide lock, so two connections never race for SQLite's write lock (no `SQLITE_BUSY`, no busy-wait
  sleeps). Pragmas: `journal_mode=WAL`, `synchronous=NORMAL` (rule 6), 1 GiB `mmap_size` and a 16 MiB page
  cache. Connections open with `SQLITE_OPEN_NOMUTEX` because each one stays on one thread.
- **Checkpoints off the event loop.** A commit that crosses `wal_autocheckpoint` checkpoints inline, and
  that means an `fsync` on the request path. Request connections instead set a high
  `wal_autocheckpoint` that serves only as a backstop. A ZIO fiber on the blocking pool runs
  `PRAGMA wal_checkpoint(PASSIVE)` every second on its own connection. In a 5-minute write-heavy run, the
  WAL stayed at a constant size.
- **The feed query.** It is the reference SQL. I benchmarked a correlated subquery for the username, a
  CTE, a derived table, and `LEFT JOIN ... GROUP BY`. None beat it, and the `GROUP BY` version was 2.5x
  slower. Building the whole JSON in SQLite with `json_group_array(json_object(...))` was no faster than
  building it in Java. The feed is ~40% of all CPU, so it skips the JDBC `ResultSet` layer and steps the
  statement through sqlite-jdbc's public low-level API: one lock for the whole query instead of one per
  call, and no per-execution column-name lookups. That makes the feed ~12% faster.
- **JSON straight from SQLite bytes.** Text columns are read as raw UTF-8 `byte[]` and copied into a
  reusable per-thread buffer, escaping only `"`, `\` and control characters. No `String`s are decoded or
  re-encoded. Each response allocates one exact-size array.
- **JWT.** HS256 is verified in full on every request (rule 5): signature, `alg`, `exp`/`nbf`, then the
  `sub`/`username` claims. The signature is checked first, with a constant-time compare, before any JSON
  is parsed. The only thing reused is each thread's `Mac`, which holds the key schedule derived from the
  secret, not any token.
- **JVM.** Serial GC: one vCPU, so there are no GC threads to compete with. Young pauses were ~10 ms
  every few seconds under heavy load. The heap is a fixed, pre-touched 512 MiB.
  `-XX:MetaspaceSize=128m` sits above the ~35 MB of classes the app loads, so metaspace growth never
  triggers a full GC (with the default, one 115 ms full GC landed during ramp-up). Compact object
  headers make objects smaller and cache use better. RSS is ~690 MB with 19k connections.
- **Netty and zio-http settings.** A listen backlog of 65535: zio-http's default is 100, too small for
  thousands of users connecting during the ramp-up, and the kernel caps it at `somaxconn` anyway. Leak
  detection is off and requests are aggregated up to 64 KiB.

## Measurements (local, not the benchmark box)

These ran on a 4-vCPU Xeon @ 2.1 GHz VM. The server was pinned to one core with `taskset`, and the load
generators ran on the other three. A process could hold at most 20,000 file descriptors, which capped
these runs at ~19k connections.

| Test | Result |
|---|---|
| `wrk -c200`, one core saturated | `/feed` ~16k req/s, `/posts/:id` ~55k req/s, `/health` ~100k req/s |
| `bench/load.js` (k6), 19,000 VUs, 2-minute hold | p95 1.7 ms, p99 7.4 ms, 0 errors, ~20% CPU, 683 MB RSS |
| Same user loop with think times ÷4, 19,000 connections, 5-minute hold | 8,040 req/s (what ~76k k6 users send), p99 11.9 ms, 0 errors, 57% CPU |
| Think times ÷8 (what ~150k users would send) | saturates around 15–16k req/s |

At 4x speed, k6 itself needed all three spare cores and became the bottleneck. The 4x and 8x rows used a
small Go program that replays `load.js`'s user loop with one keep-alive connection per user.

## License

MIT, under the repo's [license](../../LICENSE).
