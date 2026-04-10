#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT_DIR/test-apks"
ADB_BIN="${ADB:-}"

if [ -z "$ADB_BIN" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
  elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
  else
    echo "adb not found"
    exit 1
  fi
fi

if [ ! -d "$APK_DIR" ]; then
  echo "APK directory not found: $APK_DIR"
  exit 1
fi

found=0
for apk in "$APK_DIR"/*.apk; do
  if [ ! -f "$apk" ]; then
    continue
  fi
  found=1
  echo "Installing $(basename "$apk")"
  "$ADB_BIN" install -r "$apk"
done

if [ "$found" -eq 0 ]; then
  echo "No APK files found in $APK_DIR"
  exit 1
fi

echo "Install pass complete."
