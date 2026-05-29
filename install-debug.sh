#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-.android-sdk/platform-tools/adb}"
APK="app/build/outputs/apk/debug/app-debug.apk"

java_major() {
  local java_bin="${1:-java}"
  local version
  version="$("$java_bin" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')" || return 1
  if [[ "$version" == 1.* ]]; then
    version="${version#1.}"
  fi
  version="${version%%.*}"
  [[ "$version" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$version"
}

java_version_ok() {
  local major
  major="$(java_major "${1:-java}")" || return 1
  [[ "$major" -ge 17 ]]
}

java_version_preferred() {
  local major
  major="$(java_major "${1:-java}")" || return 1
  [[ "$major" -ge 17 && "$major" -le 21 ]]
}

if [[ -z "${JAVA_HOME:-}" ]] && ! java_version_preferred java; then
  selected_java=""
  selected_major=999
  while IFS= read -r candidate; do
    major="$(java_major "$candidate" 2>/dev/null || true)"
    if [[ -n "$major" && "$major" -ge 17 && "$major" -le 21 && "$major" -lt "$selected_major" ]]; then
      selected_java="$candidate"
      selected_major="$major"
    fi
  done < <(
    find "$HOME/.jdks" "$HOME/Downloads" /usr/lib/jvm -maxdepth 5 -type f -path '*/bin/java' 2>/dev/null
  )
  if [[ -n "$selected_java" ]]; then
    JAVA_HOME="$(cd "$(dirname "$selected_java")/.." && pwd)"
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

if ! java_version_ok "${JAVA_HOME:+$JAVA_HOME/bin/}java"; then
  cat >&2 <<'EOF'
This Android build needs JDK 17 or newer. JDK 17 or 21 is preferred.

Install JDK 17, or run with JAVA_HOME set to a JDK 17+ directory, then try again:

  JAVA_HOME=/path/to/jdk17 ./install-debug.sh
EOF
  exit 1
fi

./gradlew :app:assembleDebug

"$ADB" start-server >/dev/null
devices="$("$ADB" devices | awk 'NR > 1 && $1 != "" { print $1 ":" $2 }')"

if [[ -z "$devices" ]]; then
  cat >&2 <<'EOF'
No Android Debug Bridge device is available.

The phone may still appear in your file manager for storage; that is MTP and is
separate from adb. On the phone, check:

  1. Settings > About phone > Software information > tap Build number 7 times.
  2. Settings > Developer options > enable USB debugging.
  3. Unplug/replug USB, unlock the phone, and accept "Allow USB debugging".
  4. If no prompt appears: Developer options > Revoke USB debugging
     authorisations, then unplug/replug again.

Then rerun:

  ./install-debug.sh
EOF
  exit 1
fi

if grep -q ':unauthorized' <<<"$devices"; then
  "$ADB" devices -l
  cat >&2 <<'EOF'
The phone is connected but has not authorized this computer.
Unlock the phone and accept the "Allow USB debugging" prompt, then rerun this script.
EOF
  exit 1
fi

if ! grep -q ':device' <<<"$devices"; then
  "$ADB" devices -l
  cat >&2 <<'EOF'
adb sees a phone, but it is not ready for install yet. Replug USB and confirm
the USB debugging prompt on the phone.
EOF
  exit 1
fi

"$ADB" devices -l
"$ADB" install -r "$APK"
