#!/usr/bin/env bash
# Small Linux-only, checksum-pinned Gradle bootstrap (not the generated JAR wrapper).
set -euo pipefail
VERSION=8.14.5
SHA256=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854
CACHE="${GRADLE_USER_HOME:-${HOME}/.gradle}/fnord-bootstrap"
DIST="$CACHE/gradle-$VERSION"
if [[ ! -x "$DIST/bin/gradle" ]]; then
  for tool in curl unzip sha256sum flock; do
    command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
  done
  mkdir -p "$CACHE"
  (
    flock 9
    if [[ ! -x "$DIST/bin/gradle" ]]; then
      temp=$(mktemp -d "$CACHE/.download.XXXXXX")
      trap 'rm -rf "$temp"' EXIT
      curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
        "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$temp/gradle.zip"
      printf '%s  %s\n' "$SHA256" "$temp/gradle.zip" | sha256sum --check --status
      unzip -q "$temp/gradle.zip" -d "$temp"
      mv "$temp/gradle-$VERSION" "$DIST"
    fi
  ) 9>"$CACHE/install.lock"
fi
exec "$DIST/bin/gradle" "$@"
