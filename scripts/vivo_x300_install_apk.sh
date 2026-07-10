#!/usr/bin/env bash
set -euo pipefail

# Fixed-coordinate installer helper for vivo X300 / V2509A / OriginOS 6.
SERIAL="${ADB_SERIAL:-192.168.1.60:33491}"
ADB="${ADB:-adb}"
CHECK_X=607
CHECK_Y=2289
CONTINUE_X=607
CONTINUE_Y=2462
TAP_ROUNDS="${TAP_ROUNDS:-260}"
TAP_INTERVAL="${TAP_INTERVAL:-0.8}"
INSTALL_TIMEOUT="${INSTALL_TIMEOUT:-300s}"
ADB_WAIT_TIMEOUT="${ADB_WAIT_TIMEOUT:-10s}"
REMOTE_DIR="${REMOTE_DIR:-/data/local/tmp}"

usage() {
  echo "Usage: $0 APK [APK...]"
  echo "Environment: ADB_SERIAL, ADB, INSTALL_TIMEOUT, TAP_ROUNDS, TAP_INTERVAL, REMOTE_DIR"
}

[[ $# -ge 1 ]] || { usage; exit 2; }

tap_installer_loop() {
  local rounds="$1" interval="$2"
  for ((i=0; i<rounds; i++)); do
    "$ADB" -s "$SERIAL" shell input tap "$CHECK_X" "$CHECK_Y" >/dev/null 2>&1 || true
    sleep 0.15
    "$ADB" -s "$SERIAL" shell input tap "$CONTINUE_X" "$CONTINUE_Y" >/dev/null 2>&1 || true
    sleep "$interval"
  done
}

install_one() {
  local apk="$1"
  [[ -f "$apk" ]] || { echo "missing apk: $apk" >&2; return 2; }
  echo "==> installing $apk on $SERIAL"
  local remote_apk="$REMOTE_DIR/$(basename "$apk")"
  "$ADB" -s "$SERIAL" push "$apk" "$remote_apk"
  tap_installer_loop "$TAP_ROUNDS" "$TAP_INTERVAL" &
  local tap_pid=$!
  set +e
  timeout "$INSTALL_TIMEOUT" "$ADB" -s "$SERIAL" shell pm install -r -t "$remote_apk"
  local install_rc=$?
  set -e
  kill "$tap_pid" >/dev/null 2>&1 || true
  wait "$tap_pid" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell rm -f "$remote_apk" >/dev/null 2>&1 || true
  [[ "$install_rc" -eq 0 ]] || { echo "install failed or timed out for $apk (exit=$install_rc)" >&2; return "$install_rc"; }
}

timeout "$ADB_WAIT_TIMEOUT" "$ADB" -s "$SERIAL" wait-for-device
for apk in "$@"; do install_one "$apk"; done
echo "all installs finished"
