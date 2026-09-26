#!/usr/bin/env bash
# Round-trips a throwaway car through the real Firestore, then removes it.
# Runs as you, through Application Default Credentials
# (`gcloud auth application-default login`). Tokens are never printed.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :registry-firestore:smoke -PgcpProject="$PROJECT"
