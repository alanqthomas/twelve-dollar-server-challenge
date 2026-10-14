#!/usr/bin/env bash
# Runs once as root on a clean Ubuntu 24.04: installs Eclipse Temurin JDK 27 and the sbt 2.0.10 launcher.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl

JDK_DIR=/opt/temurin-27
JDK_URL=https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27%2B35/OpenJDK27U-jdk_x64_linux_hotspot_27_35.tar.gz
SBT_DIR=/opt/sbt-2.0.10
SBT_URL=https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/2.0.10/sbt-launch-2.0.10.jar
SBT_SHA1=942574260cceb1a6e6722126bd79e15dd285cb59

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 5 --retry-all-errors -o "$tmp/jdk.tar.gz" "$JDK_URL"
curl -fsSL --retry 5 --retry-all-errors -o "$tmp/jdk.tar.gz.sha256.txt" "$JDK_URL.sha256.txt"
(cd "$tmp" && echo "$(cut -d' ' -f1 jdk.tar.gz.sha256.txt)  jdk.tar.gz" | sha256sum -c -)
rm -rf "$JDK_DIR" && mkdir -p "$JDK_DIR"
tar -xzf "$tmp/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1

curl -fsSL --retry 5 --retry-all-errors -o "$tmp/sbt-launch.jar" "$SBT_URL"
echo "$SBT_SHA1  $tmp/sbt-launch.jar" | sha1sum -c -
mkdir -p "$SBT_DIR" && install -m 0644 "$tmp/sbt-launch.jar" "$SBT_DIR/sbt-launch.jar"

"$JDK_DIR/bin/java" -version
