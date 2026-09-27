#!/usr/bin/env bash
# One-time project setup. Safe to re-run: every step skips what already exists.
set -euo pipefail
source "$(dirname "$0")/env.sh"

gcloud services enable --project "$PROJECT" \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com \
  secretmanager.googleapis.com

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

# Session data (decision 9): private, in the service's region, kept indefinitely
# (no lifecycle rule). Google's default 7-day soft delete stays as a safety net.
if ! gcloud storage buckets describe "gs://$BUCKET" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud storage buckets create "gs://$BUCKET" --project "$PROJECT" --location "$REGION" \
    --uniform-bucket-level-access --public-access-prevention
fi

# The runtime account may use this bucket's objects and nothing else in Storage.
# Retried for the same policy race as above.
for attempt in 1 2 3 4 5; do
  if gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" \
      --member "serviceAccount:$RUNTIME_SA" --role roles/storage.objectAdmin >/dev/null 2>&1; then
    break
  fi
  [[ $attempt == 5 ]] && { echo "could not grant roles/storage.objectAdmin on gs://$BUCKET" >&2; exit 1; }
  sleep $((attempt * 3))
done

# Crew messages: "the car's recent messages, newest first" needs a composite index
# (found by the M5.2 smoke run). Created once; it takes a minute or two to build.
if ! gcloud firestore indexes composite list --project "$PROJECT" --format="value(name)" \
    --filter="queryScope=COLLECTION AND fields[0].fieldPath=car AND fields[1].fieldPath=sentAt" 2>/dev/null | grep -q .; then
  gcloud firestore indexes composite create --project "$PROJECT" --collection-group=messages \
    --field-config field-path=car,order=ascending --field-config field-path=sentAt,order=descending --async >/dev/null
fi

# Crew logins are signed with this key (M5.4): 32 random bytes, base64url,
# generated here and never printed. Rotating it (a new version, then a deploy)
# logs every crew member out.
if ! gcloud secrets describe crew-cookie-key --project "$PROJECT" >/dev/null 2>&1; then
  openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n' |
    gcloud secrets create crew-cookie-key --project "$PROJECT" --replication-policy automatic --data-file=- >/dev/null
fi
for attempt in 1 2 3 4 5; do
  if gcloud secrets add-iam-policy-binding crew-cookie-key --project "$PROJECT" \
      --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null 2>&1; then
    break
  fi
  [[ $attempt == 5 ]] && { echo "could not grant access to crew-cookie-key" >&2; exit 1; }
  sleep $((attempt * 3))
done

# The server checks whether its own revision still has traffic, and drains when a
# deploy has moved on (M4.8a): read-only, on this service only. The service exists
# only after the first deploy, so on a fresh project run this again after it.
if gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" >/dev/null 2>&1; then
  for attempt in 1 2 3 4 5; do
    if gcloud run services add-iam-policy-binding "$SERVICE" --project "$PROJECT" --region "$REGION" \
        --member "serviceAccount:$RUNTIME_SA" --role roles/run.viewer >/dev/null 2>&1; then
      break
    fi
    [[ $attempt == 5 ]] && { echo "could not grant roles/run.viewer on $SERVICE" >&2; exit 1; }
    sleep $((attempt * 3))
  done
else
  echo "note: $SERVICE is not deployed yet; run this again after the first deploy (roles/run.viewer)." >&2
fi


# Who may use the admin page (M6): a secret only so that it stays out of this public
# repo. Created by hand, with the admin's address, since this script can't know it:
#   printf '%s' 'someone@example.com' | gcloud secrets create admin-emails \
#     --project obd2-dashboard-backend --replication-policy automatic --data-file=-
# Comma-separated for more than one. Changing it: add a version, then deploy.
if gcloud secrets describe admin-emails --project "$PROJECT" >/dev/null 2>&1; then
  for attempt in 1 2 3 4 5; do
    if gcloud secrets add-iam-policy-binding admin-emails --project "$PROJECT" \
        --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null 2>&1; then
      break
    fi
    [[ $attempt == 5 ]] && { echo "could not grant access to admin-emails" >&2; exit 1; }
    sleep $((attempt * 3))
  done
else
  echo "note: the admin-emails secret doesn't exist yet; deploys will fail until it does (see gcp-setup.sh)." >&2
fi

# The website's domain (decision 23). Needs the gcloud beta component, the domain
# verified by the user's Google account (gcloud domains verify), and the DNS records
# this prints, at Namecheap. Google issues and renews the certificate.
if gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" >/dev/null 2>&1; then
  for domain in "${DOMAINS[@]}"; do
    if ! gcloud beta run domain-mappings describe --domain "$domain" --project "$PROJECT" --region "$REGION" >/dev/null 2>&1; then
      gcloud beta run domain-mappings create --service "$SERVICE" --domain "$domain" \
        --project "$PROJECT" --region "$REGION" --quiet
    fi
  done
fi
