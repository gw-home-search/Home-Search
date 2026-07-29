locals {
  generated_runtime_parameter_names = toset([
    "postgres/superuser-password",
    "postgres/property-runtime-password",
    "postgres/property-migrator-password",
    "postgres/property-importer-password",
    "postgres/property-ai-reader-password",
    "postgres/user-runtime-password",
    "postgres/user-migrator-password",
    "postgres/admin-runtime-password",
    "postgres/admin-migrator-password",
    "postgres/ai-runtime-password",
    "postgres/ai-migrator-password",
    "postgres/ai-importer-password",
    "postgres/backup-password",
    "valkey/property-password",
    "valkey/bff-password",
    "valkey/admin-password",
    "edge/certificate-passphrase",
    "user/jwt-private-key-pem",
    "user/jwt-public-key-pem",
    "admin/jwt-private-key-pem",
    "admin/jwt-public-key-pem",
    "ai/property-dsn",
    "ai/reference-dsn",
    "ai/migrator-dsn",
  ])
  external_runtime_parameter_names = toset([
    "property/kakao-rest-api-key",
    "user/oauth/kakao-client-id",
    "user/oauth/kakao-client-secret",
    "user/oauth/google-client-id",
    "user/oauth/google-client-secret",
    "user/oauth/naver-client-id",
    "user/oauth/naver-client-secret",
    "ai/openai-api-key",
    "ai/openai-primary-model",
    "ai/openai-secondary-model",
  ])
  runtime_parameter_names = setunion(
    local.generated_runtime_parameter_names,
    local.external_runtime_parameter_names,
  )
}

resource "aws_ssm_parameter" "runtime" {
  for_each    = local.foundation_enabled ? local.runtime_parameter_names : toset([])
  name        = "/home-search/budget-production/${each.value}"
  description = "Budget production protected value container; populated out-of-band after foundation apply."
  type        = "SecureString"
  value       = "UNSET"

  lifecycle {
    ignore_changes  = [value]
    prevent_destroy = true
  }

  tags = { DataClass = "secret", ParameterStatus = "out-of-band" }
}
