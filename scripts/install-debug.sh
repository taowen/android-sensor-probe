#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ADB_SERIAL:-192.168.1.60:33491}"
JAVA_HOME="${JAVA_HOME:-/home/linuxbrew/.linuxbrew/opt/openjdk@21/libexec}"
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ "${1:-}" != "--no-build" ]]; then
  (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug)
fi

ADB_SERIAL="$SERIAL" "$ROOT_DIR/scripts/vivo_x300_install_apk.sh" "$APK"
adb -s "$SERIAL" shell am force-stop io.github.sensorprobe
adb -s "$SERIAL" shell monkey -p io.github.sensorprobe 1 >/dev/null
echo "Installed and launched io.github.sensorprobe on $SERIAL"
