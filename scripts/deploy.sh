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

# --allow-unauthenticated: the website is public (decision 11); tablet routes
# check the car's token themselves (decision 10).
# --max-instances 1: tablets and browsers must meet in one process (decision 7).
# --timeout 3600: a socket or stream would otherwise be cut at 5 minutes; the
#   server closes its own at 55 (contract §5.3). --concurrency 1000: every open
#   socket and browser stream is a request, and the default 80 would refuse the 81st.
# --clear-secrets: a deploy keeps any setting it does not mention, so the old
# shared key (decision 4) must be detached explicitly. M5's cookie secret will
# replace this with its own --set-secrets.
gcloud run deploy "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --image "$IMAGE" \
  --service-account "$RUNTIME_SA" \
  --clear-secrets \
  --set-env-vars "GCP_PROJECT=${PROJECT},SESSIONS_BUCKET=${BUCKET}" \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 1 \
  --timeout 3600 \
  --concurrency 1000 \
  --quiet
