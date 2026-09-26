#!/usr/bin/env bash
# One-time project setup. Safe to re-run: every step skips what already exists.
set -euo pipefail
source "$(dirname "$0")/env.sh"

gcloud services enable --project "$PROJECT" \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com

# The service runs as its own account, which can read the tablet key and nothing
# else, rather than as the broad default compute account.
if ! gcloud iam service-accounts describe "$RUNTIME_SA" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${RUNTIME_SA%%@*}" --project "$PROJECT" \
    --display-name "obd2-backend Cloud Run runtime"
fi

# The key is generated here and never printed. Read it with scripts/tablet-key.sh.
if ! gcloud secrets describe "$SECRET" --project "$PROJECT" >/dev/null 2>&1; then
  openssl rand -base64 33 | tr '+/' '-_' | tr -d '\n' |
    gcloud secrets create "$SECRET" --project "$PROJECT" \
      --replication-policy automatic --data-file=-
fi

gcloud secrets add-iam-policy-binding "$SECRET" --project "$PROJECT" \
  --member "serviceAccount:$RUNTIME_SA" \
  --role roles/secretmanager.secretAccessor >/dev/null
