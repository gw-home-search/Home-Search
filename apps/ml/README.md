# F37 Prediction Service

FastAPI service for `deployment__F37_monthly_anchor_prev3_rolling_huber_010`.

The service only loads the F37 artifact and runs inference. Spring Boot owns DB
feature generation, Redis cache state, public API formatting, and failure
isolation.

## Environment

```bash
F37_ARTIFACT_DIR=/Users/gwongwangjae/goorm-ai-language-course/final_project/models/best_price_deployment_attempt
```

The artifact directory is not committed to this repository.

## Serve

```bash
cd apps/ml
F37_ARTIFACT_DIR=/path/to/best_price_deployment_attempt \
  uvicorn ml_service.main:app --host 127.0.0.1 --port 8001
```

## Smoke predict

```bash
cd apps/ml
F37_ARTIFACT_DIR=/path/to/best_price_deployment_attempt \
  python -m ml_service.smoke_predict
```
