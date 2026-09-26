#!/usr/bin/env bash
# Print the current tablet key, to paste into the app's settings.
set -euo pipefail
source "$(dirname "$0")/env.sh"
gcloud secrets versions access latest --secret "$SECRET" --project "$PROJECT"
echo
