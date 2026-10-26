#!/usr/bin/env bash
# M32.6: moves races' hand-edited stints into their sessions' drivers, once (decision 48).
# Runs as you (Application Default Credentials), against the project and bucket in env.sh.
#   scripts/migrate-stints.sh            a dry run: each race before and after, and what it would write
#   scripts/migrate-stints.sh --apply    writes the sessions' drivers, only if every race matches
#   scripts/migrate-stints.sh --clear    after the deploy: empties the edited stints, only if the stored drivers match
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
GCP_PROJECT="$PROJECT" SESSIONS_BUCKET="$BUCKET" exec ./gradlew -q -PskipWeb :server:migrateStints --args="${1:---dry-run}"
