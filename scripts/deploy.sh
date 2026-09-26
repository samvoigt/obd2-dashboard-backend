#!/usr/bin/env bash
# Build from the Dockerfile on Cloud Build and deploy to Cloud Run.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."

# --allow-unauthenticated: the website is public (decision 5); tablet routes
# check the key themselves (decision 4).
gcloud run deploy "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --source . \
  --service-account "$RUNTIME_SA" \
  --set-secrets "TABLET_API_KEY=${SECRET}:latest" \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 2 \
  --quiet
