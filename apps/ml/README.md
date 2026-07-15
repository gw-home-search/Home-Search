# F37 Prediction Service

FastAPI service for `deployment__F37_monthly_anchor_prev3_rolling_huber_010`.

The service only loads the F37 artifact and runs inference. Spring Boot owns DB
feature generation, Redis cache state, public API formatting, and failure
isolation.

The runtime image installs `requirements.lock` during build. It does not install
packages at container startup, mount the source tree, or copy a model artifact
into an image layer. `requirements.txt` records human-maintained direct
dependency ranges; `requirements.lock` is the exact application/transitive
graph used by Docker and CI.

## Environment

```bash
F37_ARTIFACT_DIR=/Users/gwongwangjae/goorm-ai-language-course/final_project/models/best_price_deployment_attempt
```

The artifact directory is not committed to this repository.

The container runs as UID/GID `10001:10001`. A Linux host artifact directory
must therefore allow that identity to read every F37 file. Compose mounts the
directory at `/model:ro`; missing or unreadable `keras_model.keras` fails before
Uvicorn starts.

## Image

```bash
docker build --tag home-search-ml:local apps/ml
docker run --rm --entrypoint python home-search-ml:local -m pip check
```

Run with the external model:

```bash
F37_ARTIFACT_DIR=/path/to/best_price_deployment_attempt \
  docker compose -f infra/docker-compose.local.yml up -d --build ml

curl --fail http://127.0.0.1:8001/health
```

The API service does not depend on ML container readiness. Prediction provider
failure stays isolated in the Spring prediction adapter rather than blocking
map startup.

## Serve

```bash
cd apps/ml
F37_ARTIFACT_DIR=/path/to/best_price_deployment_attempt \
  uvicorn ml_service.main:app --host 127.0.0.1 --port 8001
```

For a local Python environment, install `requirements.lock`; do not use the
range file as a deployment input.

## Smoke predict

```bash
cd apps/ml
F37_ARTIFACT_DIR=/path/to/best_price_deployment_attempt \
  python -m ml_service.smoke_predict
```

CI builds the image on amd64, runs `pip check`, imports the runtime modules,
asserts the non-root identity and model exclusion, and verifies the entrypoint
fails without a model. Before updating the lock, validate the new graph on both
amd64 CI and the local arm64 image, then rerun the real model health and sample
prediction checks.
