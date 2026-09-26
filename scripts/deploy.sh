#!/usr/bin/env bash
# Build on Cloud Build (cloudbuild.yaml) and deploy the image to Cloud Run.
#
# Not `gcloud run deploy --source`: that submits a build without a logging
# option, which this project refuses — see cloudbuild.yaml.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$(dirname "$0")/.."

TAG="$(git rev-parse --short HEAD)"
[[ -z "$(git status --porcelain)" ]] || TAG="${TAG}-dirty-$(date +%s)"
IMAGE="${IMAGE_BASE}:${TAG}"

gcloud builds submit --project "$PROJECT" --config cloudbuild.yaml \
  --substitutions "_IMAGE=${IMAGE}" .

# --allow-unauthenticated: the website is public (decision 5); tablet routes
# check the key themselves (decision 4).
gcloud run deploy "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --image "$IMAGE" \
  --service-account "$RUNTIME_SA" \
  --set-secrets "TABLET_API_KEY=${SECRET}:latest" \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 2 \
  --quiet
