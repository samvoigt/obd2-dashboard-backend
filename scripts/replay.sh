#!/usr/bin/env bash
# Uploads session logs as the tablet does (contract §6), faults on demand.
# The car's token comes from OBD2_TOKEN or --token-file, never an argument.
#   OBD2_TOKEN=… scripts/replay.sh --server https://… session.jsonl.gz
#   scripts/replay.sh --token-file tok --server https://… --lose-responses 0.3 --duplicate 0.2 a.jsonl b.jsonl.gz
#   scripts/replay.sh --help
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q :replay:installDist
exec replay/build/install/replay/bin/replay "$@"
