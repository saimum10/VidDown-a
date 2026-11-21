#!/usr/bin/env bash
# Runs `connectedDebugAndroidTest` against the emulator that reactivecircus/android-emulator-runner started, but only
# after proving the emulator is READY and COMPATIBLE with the APK that is about to be installed.
#
# Why this exists (poc.yml): AGP reports every unusable device as
#     "No compatible devices connected. Found 1 connected device(s), 0 of which were compatible."
# and logs the actual reason only at --info level, so a failed run could not say whether the emulator was still booting,
# had gone `offline`, had the wrong API level or did not support the packaged ABI. This script checks each of those
# BEFORE Gradle starts and fails with the specific reason; when Gradle itself fails it dumps the device state so the
# next log shows what AGP saw. It never retries tests, never skips them and never edits them.
#
# Usage: scripts/run_instrumented_tests.sh <packaged-abi> <expected-api-level> [extra gradle arguments...]
#   e.g. scripts/run_instrumented_tests.sh x86 34 --stacktrace -PnativeAbis=x86
#
# Environment (all optional; the defaults are what CI uses):
#   ADB, GRADLEW            executables (tests substitute fakes)
#   READY_TIMEOUT           seconds to wait for a booted, online device (300)
#   POLL_INTERVAL           seconds between polls (2)
#   OFFLINE_RECONNECT_AFTER seconds a device may stay `offline` before `adb reconnect offline` is issued (30)
#   DIAG_FILE               where device diagnostics are written (build/ci/device-diagnostics.txt)
set -uo pipefail

ADB="${ADB:-adb}"
GRADLEW="${GRADLEW:-./gradlew}"
READY_TIMEOUT="${READY_TIMEOUT:-300}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
OFFLINE_RECONNECT_AFTER="${OFFLINE_RECONNECT_AFTER:-30}"
DIAG_FILE="${DIAG_FILE:-build/ci/device-diagnostics.txt}"
MIN_SDK=24 # keep equal to minSdk in app/build.gradle.kts, data/build.gradle.kts and core/build.gradle.kts

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <packaged-abi> <expected-api-level> [gradle args...]" >&2
  exit 2
fi
ABI="$1"
EXPECTED_API="$2"
shift 2

mkdir -p "$(dirname "$DIAG_FILE")"
: > "$DIAG_FILE"

log() { echo "[device-gate] $*"; }
note() { echo "$*" | tee -a "$DIAG_FILE"; }

# Prints "<serial><TAB><state>" for every device adb knows about (daemon banner lines have no TAB and are ignored).
list_devices() {
  "$ADB" devices 2> /dev/null | tr -d '\r' | awk -F'\t' 'NF >= 2 && $1 != "List of devices attached" { print $1 "\t" $2 }'
}

prop() { # prop <serial> <name>
  "$ADB" -s "$1" shell getprop "$2" 2> /dev/null | tr -d '\r\n'
}

dump_device_state() {
  {
    echo "===== adb devices -l ====="
    "$ADB" devices -l 2>&1 | tr -d '\r'
    local line serial
    while IFS=$'\t' read -r serial _; do
      [ -n "$serial" ] || continue
      echo "===== $serial ====="
      for p in ro.product.model ro.build.version.sdk ro.build.version.release ro.product.cpu.abilist ro.product.cpu.abilist32 \
        ro.product.cpu.abilist64 ro.zygote sys.boot_completed init.svc.bootanim; do
        echo "$p=$(prop "$serial" "$p")"
      done
      echo "--- logcat (last 200 lines) ---"
      "$ADB" -s "$serial" logcat -d -t 200 2>&1 | tr -d '\r'
    done < <(list_devices)
  } >> "$DIAG_FILE" 2>&1
  log "device diagnostics written to $DIAG_FILE"
  head -n 40 "$DIAG_FILE"
}

fail() {
  log "ERROR: $*"
  note "ERROR: $*"
  dump_device_state
  exit 1
}

# ---- 1. wait for exactly one device that is `device` (not offline) and fully booted -------------------------------------
"$ADB" start-server > /dev/null 2>&1 || true
log "waiting up to ${READY_TIMEOUT}s for the emulator (ABI under test: $ABI, expected API: $EXPECTED_API)"
deadline=$((SECONDS + READY_TIMEOUT))
offline_since=""
serial=""
reason="no device listed by adb"
while :; do
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -gt 1 ]; then
    fail "adb lists ${#devices[@]} devices (${devices[*]//$'\t'/ :}); AGP would run on all of them. Expected exactly one emulator."
  elif [ "${#devices[@]}" -eq 1 ]; then
    IFS=$'\t' read -r serial state <<< "${devices[0]}"
    if [ "$state" = "device" ]; then
      offline_since=""
      boot="$(prop "$serial" sys.boot_completed)"
      anim="$(prop "$serial" init.svc.bootanim)"
      if [ "$boot" != "1" ]; then
        reason="$serial is online but sys.boot_completed='$boot'"
      elif [ -n "$anim" ] && [ "$anim" != "stopped" ]; then
        reason="$serial boot animation is '$anim'"
      elif ! "$ADB" -s "$serial" shell pm path android > /dev/null 2>&1; then
        reason="$serial is booted but the package manager is not answering yet"
      else
        break
      fi
    else
      reason="$serial is in adb state '$state' (needs 'device')"
      if [ "$state" = "offline" ]; then
        : "${offline_since:=$SECONDS}"
        if [ $((SECONDS - offline_since)) -ge "$OFFLINE_RECONNECT_AFTER" ]; then
          log "$serial has been offline for $((SECONDS - offline_since))s: adb reconnect offline"
          "$ADB" reconnect offline > /dev/null 2>&1 || true
          offline_since="$SECONDS"
        fi
      fi
    fi
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    fail "emulator not ready after ${READY_TIMEOUT}s: $reason"
  fi
  sleep "$POLL_INTERVAL"
done
log "$serial is online and fully booted"

# ---- 2. the emulator must match what the packaged APK needs ---------------------------------------------------------------
sdk="$(prop "$serial" ro.build.version.sdk)"
abilist="$(prop "$serial" ro.product.cpu.abilist)"
note "device=$serial api=$sdk abilist=$abilist packaged-abi=$ABI expected-api=$EXPECTED_API minSdk=$MIN_SDK"

case "$sdk" in '' | *[!0-9]*) fail "could not read the device API level (ro.build.version.sdk='$sdk')" ;; esac
if [ "$sdk" -lt "$MIN_SDK" ]; then
  fail "device API $sdk is below minSdk $MIN_SDK of the app: AGP would call it incompatible"
fi
if [ "$sdk" != "$EXPECTED_API" ]; then
  fail "device API is $sdk but the matrix cell expects $EXPECTED_API: the emulator-runner api-level does not match"
fi
case ",$abilist," in
  *",$ABI,"*) ;;
  *) fail "this system image supports ABIs [$abilist] but the APK under test packages only '$ABI' (-PnativeAbis). The APK cannot be installed here, so AGP reports the device as incompatible. Pick a system image whose ABI list includes '$ABI'." ;;
esac
log "device matches: API $sdk >= minSdk $MIN_SDK, ABI '$ABI' is in [$abilist]"

# ---- 3. run the instrumented tests (all modules, debug variant) -----------------------------------------------------------
"$GRADLEW" --no-daemon connectedDebugAndroidTest "$@"
status=$?
if [ "$status" -ne 0 ]; then
  log "Gradle exited with $status; capturing the device state AGP saw"
  dump_device_state
fi
exit "$status"
