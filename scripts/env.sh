# Shared by the scripts in this directory. Source it; don't run it.
PROJECT=obd2-dashboard-backend
REGION=us-east4
SERVICE=obd2-backend
RUNTIME_SA="obd2-backend-run@${PROJECT}.iam.gserviceaccount.com"
REPO=obd2-backend
IMAGE_BASE="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}/server"
BUCKET="${PROJECT}-sessions"
# The website's own address (decision 23). The run.app URL stays too: the app has it built in.
DOMAINS=(badnewsbears.live www.badnewsbears.live)
# "Sign in with Google" for the admin page (M6). Public by nature: every visitor's
# browser receives it. Empty until the OAuth client exists; then admin is off.
GOOGLE_CLIENT_ID="286164118741-s19us3m2ctv9b8l883dja6oc5sk2ikfb.apps.googleusercontent.com"
