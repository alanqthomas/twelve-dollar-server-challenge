#!/usr/bin/env bash
# Runs the server in the foreground. Config: SQLITE_PATH, JWT_SECRET, HOST, PORT.
#
# JVM: Serial GC (one vCPU: no GC threads to compete with), a fixed pre-touched 512 MiB heap (~55 MB is live
# with 19k open connections), MetaspaceSize above what the app loads (~35 MB) so metaspace growth never
# triggers a full GC, and compact object headers (smaller objects, better cache use).
#
# io_uring keeps Netty's default 4,096-entry completion queue. A 65,536-entry one measured fine on a desktop but,
# on the droplet at 30,000 users, made io_uring_enter take over half the event loop's time (16% with the default)
# and the server collapsed.
set -euo pipefail
cd "$(dirname "$0")"
exec /opt/temurin-27/bin/java \
  -XX:+UseSerialGC -Xms512m -Xmx512m -XX:+AlwaysPreTouch -XX:MetaspaceSize=128m \
  -XX:+UseCompactObjectHeaders -XX:-UsePerfData \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -jar target/server.jar
