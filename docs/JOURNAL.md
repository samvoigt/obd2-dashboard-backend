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
