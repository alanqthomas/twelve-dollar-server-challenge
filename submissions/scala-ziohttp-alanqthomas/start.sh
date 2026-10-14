#!/usr/bin/env bash
# Runs the server in the foreground. Config: SQLITE_PATH, JWT_SECRET, HOST, PORT.
set -euo pipefail
cd "$(dirname "$0")"
exec /opt/temurin-27/bin/java \
  -XX:+UseSerialGC -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
  -XX:+UseCompactObjectHeaders -XX:-UsePerfData \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -jar target/server.jar
