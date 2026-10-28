#!/usr/bin/env bash
# Throwaway merges through the real Firestore, then deleted (M33.2). Runs as you (ADC).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :archive-gcp:mergeSmoke -PgcpProject="$PROJECT"
