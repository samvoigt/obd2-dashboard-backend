#!/usr/bin/env bash
# The owner's tool for cars (add, rotate tokens, set passcodes, list, remove)
# and sessions (list, show, delete).
# Runs as you (Application Default Credentials), against the project in env.sh.
#   scripts/admin.sh --help
#   scripts/admin.sh add-car yaris --name "Yaris"
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."
# Built rather than `gradlew run`: reading a passcode without echo needs the
# real terminal, which Gradle does not pass through.
./gradlew -q :tools:installDist
exec tools/build/install/admin/bin/admin --project "$PROJECT" --bucket "$BUCKET" "$@"
