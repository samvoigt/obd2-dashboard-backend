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

## 2026-09-26 — M2.2, Firestore

- **Google's policy race, again.** A project IAM binding made right after
  creating the Firestore database failed with "concurrent policy changes":
  Google was adding its own Firestore service agent at that moment. This is the
  same race as the first deploy. `gcp-setup.sh` now retries the binding.
  **Expect it after enabling any API.**
- **`Precondition.exists` is package-private** in the Firestore Java client, so
  "delete only if it exists" is a transaction instead.

## 2026-09-26 — M2 deploy

- **A Cloud Run deploy keeps any setting it does not mention.** Dropping
  `--set-secrets` from `deploy.sh` would have left `TABLET_API_KEY` mounted, and
  after the secret was deleted the next revision would have failed to start.
  This was caught while validating the M2.6 plan, not in production. Removing a
  setting needs an explicit flag (`--clear-secrets`, `--remove-env-vars`).
- **The service has two URLs.** gcloud 586 prints the newer form,
  `obd2-backend-286164118741.us-east4.run.app`. The older
  `obd2-backend-qeppiy7nzq-uk.a.run.app`, which the contract names, still
  serves. Both answer.
- **Validating each step's plan against the code just built paid off** (Sam's
  instruction). It found five conflicts, each before it could cost anything:
  - Firestore needed its own module, to keep `:registry` pure;
  - `CarAuth` had to sit beside `TabletAuth` until the deploy;
  - `--clear-secrets` (above);
  - the Ktor bearer provider cannot change its `401` body;
  - `Precondition.exists` is not public.
