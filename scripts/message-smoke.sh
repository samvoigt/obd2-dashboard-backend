#!/usr/bin/env bash
# Crew messages through the real Firestore, then deleted. Runs as you (ADC).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :archive-gcp:messageSmoke -PgcpProject="$PROJECT"
