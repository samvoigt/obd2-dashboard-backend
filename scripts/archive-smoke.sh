#!/usr/bin/env bash
# Uploads, completes, downloads and deletes a throwaway session against the real
# bucket and database. Runs as you (Application Default Credentials).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
./gradlew -q :archive-gcp:smoke -PgcpProject="$PROJECT" -Pbucket="$BUCKET"
