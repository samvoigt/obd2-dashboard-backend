#!/usr/bin/env bash
# Drivers and an event through the real Firestore, then deleted (M14.2). Runs as you (ADC).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :archive-gcp:eventSmoke -PgcpProject="$PROJECT"
