#!/usr/bin/env bash
# A throwaway user and access record through the real Firestore, then deleted (M23). Runs as you (ADC).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :archive-gcp:userSmoke -PgcpProject="$PROJECT"
