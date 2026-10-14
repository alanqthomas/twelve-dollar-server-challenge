#!/usr/bin/env bash
# Builds target/server.jar as a normal user. sbt downloads the pinned dependencies from Maven Central.
set -euo pipefail
cd "$(dirname "$0")"
exec /opt/temurin-27/bin/java -Xmx1g -Dsbt.ci=true -Dsbt.supershell=false \
  -jar /opt/sbt-2.0.10/sbt-launch.jar assembly
