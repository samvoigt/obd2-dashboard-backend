#!/usr/bin/env bash
# Courses through the real Firestore, then deleted (M12.3). Runs as you (ADC).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :archive-gcp:courseSmoke -PgcpProject="$PROJECT"
