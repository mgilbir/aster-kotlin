#!/usr/bin/env bash
# JVM-only tests for the platform-independent core. No Android SDK or device needed.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew \
  :vega-model:test \
  :vega-expression:test \
  :vega-dataflow:test \
  :vega-scene:test \
  :vega-runtime:test \
  :vega-svg:test
