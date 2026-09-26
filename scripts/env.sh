# Shared by the scripts in this directory. Source it; don't run it.
PROJECT=obd2-dashboard-backend
REGION=us-east4
SERVICE=obd2-backend
RUNTIME_SA="obd2-backend-run@${PROJECT}.iam.gserviceaccount.com"
REPO=obd2-backend
IMAGE_BASE="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}/server"
