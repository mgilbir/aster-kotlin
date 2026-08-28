#!/usr/bin/env bash
# JVM-only tests for the platform-independent core. No Android SDK or device needed.
#
# **Every core module, listed once here and derived from the settings file.** It used to name six by
# hand, and it predated two of them: `vega-lite` and `vega-loader` were never added, so about eight
# hundred tests — the whole Vega-Lite compiler among them — were absent from what README.md sells as
# the JVM suite. A broken Vega-Lite compiler passed this script cleanly.
#
# Derived rather than listed, so the next module added is in it. What is excluded is what genuinely
# needs a device or a Mac: the Android surfaces, the demo, the benchmark harness, and
# `vega-compose-multiplatform`, whose JVM tests run under `check.sh` with the Compose test runtime.
set -euo pipefail
cd "$(dirname "$0")/.."

# `test` on a Kotlin Multiplatform module runs the JVM target's tests; on a plain JVM module it is
# the only target there is.
# A `while read` loop rather than `readarray`, which is bash 4 and macOS ships 3.2 as /bin/bash.
modules=()
while IFS= read -r module; do
  modules+=("$module")
done < <(
  sed -n 's/^include("\(:[a-z-]*\)")$/\1/p' settings.gradle.kts |
    grep -Ev '^:(demo|benchmark|vega-android-canvas|vega-compose|vega-compose-multiplatform)$'
)
if [ "${#modules[@]}" -eq 0 ]; then
  echo "error: no modules found in settings.gradle.kts; the pattern above has drifted" >&2
  exit 1
fi
echo "Testing: ${modules[*]}"

./gradlew "${modules[@]/%/:test}"
