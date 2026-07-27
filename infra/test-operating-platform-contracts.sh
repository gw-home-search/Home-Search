#!/usr/bin/env bash
set -euo pipefail

bash .github/scripts/test-action-pinning.sh
bash .github/scripts/test-ci-main-trigger.sh
bash .github/scripts/test-event-contracts.sh
bash .github/scripts/test-event-contract-baseline.sh
bash .github/scripts/test-release-contract-metadata.sh
bash .github/scripts/test-production-deploy-contract.sh
bash .github/scripts/test-staging-news-release-contract.sh
bash infra/deploy/test-deploy-scripts.sh
bash infra/nginx/test-public-gateway-routing.sh
bash infra/test-local-event-stack.sh
bash infra/images/test-base-image-pinning.sh
bash infra/images/test-base-image-pinning-contract.sh
bash infra/release/test-vulnerability-policy.sh
bash infra/release/test-create-release-manifest.sh
bash .github/scripts/test-classify-changes.sh
bash infra/nginx/test-property-public.sh
bash infra/postgres/verify-service-boundaries.sh
bash infra/test-compose-config.sh
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform/bootstrap validate
terraform -chdir=infra/terraform/bootstrap test
terraform -chdir=infra/terraform/staging validate
terraform -chdir=infra/terraform/staging test
terraform -chdir=infra/terraform/production validate
terraform -chdir=infra/terraform/production test

printf '상태: Pass - operating platform contract와 Terraform gate를 확인했습니다.\n'
