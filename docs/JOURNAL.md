# Journal

Measurements from real deployments, bugs found in the field that tests could
not catch, and lessons about process. Short on purpose.

---

## 2026-09-26 — First deploy

- **`/healthz` never reaches the server on Cloud Run.** Google's front end
  answers it with its own HTML 404. Cloud Run reserves some paths that end in
  `z`. Renamed to `/health`. The local tests could not have caught this.
- **`gcloud run deploy --source` is refused with a bare `PERMISSION_DENIED`**
  on this project. The real cause only showed up when the Cloud Build API was
  called directly: builds run as the compute default SA, which cannot write the
  legacy logs bucket. Fixed with `cloudbuild.yaml` and `CLOUD_LOGGING_ONLY`.
- Local `gcloud` is 418.0 (2023), and `artifacts repositories create` fails on
  it. The setup script calls the REST API for that step instead.

## 2026-09-26 — gcloud 586 needs Python 3.10+

- After an update to 586.0.0, `gcloud` would not start at all: *"You are
  running gcloud with Python 3.9, which is no longer supported"*. The Mac had
  only 3.9 (both system and Homebrew). Fixed by installing Homebrew
  `python@3.13` and setting `CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.13` in
  `~/.zshrc`. If gcloud breaks the same way after a Homebrew cleanup, check
  that path first.
