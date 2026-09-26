#!/usr/bin/env bash
# One-time project setup. Safe to re-run: every step skips what already exists.
set -euo pipefail
source "$(dirname "$0")/env.sh"

gcloud services enable --project "$PROJECT" \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com

if ! gcloud artifacts repositories describe "$REPO" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; then
  # REST rather than `gcloud artifacts repositories create`: gcloud 418 sends
  # a Maven config alongside the Docker one and the API rejects it.
  curl -sf -X POST \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H "Content-Type: application/json" \
    "https://artifactregistry.googleapis.com/v1/projects/${PROJECT}/locations/${REGION}/repositories?repositoryId=${REPO}" \
    -d '{"format":"DOCKER"}' >/dev/null
  until gcloud artifacts repositories describe "$REPO" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; do sleep 2; done
fi

# The service runs as its own account, which can use Firestore and nothing else,
# rather than as the broad default compute account.
if ! gcloud iam service-accounts describe "$RUNTIME_SA" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${RUNTIME_SA%%@*}" --project "$PROJECT" \
    --display-name "obd2-backend Cloud Run runtime"
fi

# Cars live in Firestore (decision 9). The location is permanent once created,
# so it is the service's own region, never a default.
if ! gcloud firestore databases describe --database="(default)" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud firestore databases create --database="(default)" --project "$PROJECT" \
    --location "$REGION" --type firestore-native
fi

# Retried: enabling an API makes Google add its own service agents to the project
# policy, and a binding made at that moment fails with "concurrent policy
# changes" (JOURNAL 2026-09-26).
for attempt in 1 2 3 4 5; do
  if gcloud projects add-iam-policy-binding "$PROJECT" \
      --member "serviceAccount:$RUNTIME_SA" \
      --role roles/datastore.user --condition None >/dev/null 2>&1; then
    break
  fi
  [[ $attempt == 5 ]] && { echo "could not grant roles/datastore.user" >&2; exit 1; }
  sleep $((attempt * 3))
done
