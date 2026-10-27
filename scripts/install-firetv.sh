#!/usr/bin/env bash
# Build the SmarterTube (stlocal) debug APK and install it on a Fire TV over the network.
# Usage: scripts/install-firetv.sh <fire-tv-ip>
# Set ABI=arm64-v8a or ABI=universal to install a different APK (default armeabi-v7a).
set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <fire-tv-ip>" >&2
    exit 1
fi

IP="$1"
ABI="${ABI:-armeabi-v7a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# adb and the JDK come from the devenv shell; re-run inside it if they're missing
if ! command -v adb >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    exec devenv shell -- bash "$0" "$@"
fi

if [ ! -f keystore.properties ]; then
    echo "Note: no keystore.properties, so this APK is signed with a local debug key."
    echo "Updates will only install over it from this same checkout. See FORK.md."
fi

echo "Connecting to $IP..."
if ! adb connect "$IP:5555" | tee /dev/stderr | grep -q "connected to"; then
    echo "Could not connect. On the Fire TV turn on Settings > My Fire TV > Developer options > ADB debugging," >&2
    echo "check the IP under Settings > My Fire TV > About > Network, and accept the prompt on the TV." >&2
    exit 1
fi

echo "Building..."
bash gradlew :smarttubetv:assembleStlocalDebug

APK="$(ls smarttubetv/build/outputs/apk/stlocal/debug/SmartTube_local_*_"$ABI".apk | head -n 1)"
echo "Installing $APK..."
adb -s "$IP:5555" install -r "$APK"

echo "Done. Launch SmarterTube from the Fire TV apps list."
