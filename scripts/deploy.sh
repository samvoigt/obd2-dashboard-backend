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
# --memory 1Gi: the JVM's heap is 512 MiB (the Dockerfile), and it needs ~200 MB
#   of its own beside it (decision 41).
# --set-secrets replaces every secret the service mounts with exactly these: the
# crew-login signing key (M5.4), and the admin allowlist (M6), kept out of this
# public repo. A deploy keeps any setting it does not mention, so naming the
# whole set here is what keeps it exact.
gcloud run deploy "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --image "$IMAGE" \
  --service-account "$RUNTIME_SA" \
  --set-secrets "CREW_COOKIE_KEY=crew-cookie-key:latest,ADMIN_EMAILS=admin-emails:latest" \
  --set-env-vars "GCP_PROJECT=${PROJECT},SESSIONS_BUCKET=${BUCKET}${GOOGLE_CLIENT_ID:+,GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}}" \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 1 \
  --timeout 3600 \
  --concurrency 1000 \
  --memory 1Gi \
  --quiet
