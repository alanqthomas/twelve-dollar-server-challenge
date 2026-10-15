#!/usr/bin/env bash
# Runs the server in the foreground. Config: SQLITE_PATH, JWT_SECRET, HOST, PORT.
#
# JVM: Serial GC (one vCPU: no GC threads to compete with), a fixed pre-touched 512 MiB heap (~55 MB is live
# with 19k open connections), MetaspaceSize above what the app loads (~35 MB) so metaspace growth never
# triggers a full GC, and compact object headers (smaller objects, better cache use).
#
# io_uring: a 65,536-entry completion queue (Netty's default is 4,096), one slot for every connection the
# 65,535-descriptor limit allows. On a shared vCPU, a steal spike stalls the event loop while completions keep
# arriving; with 4,096 slots the queue overflowed during the recovery. It costs ~1 MB and no measurable CPU.
set -euo pipefail
cd "$(dirname "$0")"
exec /opt/temurin-27/bin/java \
  -XX:+UseSerialGC -Xms512m -Xmx512m -XX:+AlwaysPreTouch -XX:MetaspaceSize=128m \
  -XX:+UseCompactObjectHeaders -XX:-UsePerfData \
  -Dio.netty.iouring.cqSize=65536 \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -jar target/server.jar
