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
    "property/apt-service-key",
    "property/news/naver-client-id",
    "property/news/naver-client-secret",
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
  retained_runtime_parameter_name = "property/apt-service-key"
  managed_runtime_parameter_names = setsubtract(local.runtime_parameter_names, toset([local.retained_runtime_parameter_name]))
}

resource "aws_ssm_parameter" "runtime" {
  for_each         = local.foundation_enabled ? local.managed_runtime_parameter_names : toset([])
  name             = "/home-search/budget-production/${each.value}"
  description      = "Budget production protected value container; populated out-of-band after foundation apply."
  type             = "SecureString"
  value_wo         = "UNSET"
  value_wo_version = 1

  lifecycle {
    prevent_destroy = true
  }

  tags = { DataClass = "secret", ParameterStatus = "out-of-band" }
}

resource "aws_ssm_parameter" "retained_apt_service_key" {
  provider         = aws.retained_ssm
  count            = local.foundation_enabled ? 1 : 0
  name             = "/home-search/budget-production/${local.retained_runtime_parameter_name}"
  description      = "Budget production RTMS provider key; populated out-of-band."
  type             = "SecureString"
  value_wo         = "UNSET"
  value_wo_version = 1

  lifecycle {
    prevent_destroy = true
  }

  tags = { DataClass = "secret", ParameterStatus = "out-of-band" }
}

moved {
  from = aws_ssm_parameter.runtime["property/apt-service-key"]
  to   = aws_ssm_parameter.retained_apt_service_key[0]
}

locals {
  runtime_parameter_arns = merge(
    { for name, parameter in aws_ssm_parameter.runtime : name => parameter.arn },
    local.foundation_enabled ? {
      (local.retained_runtime_parameter_name) = aws_ssm_parameter.retained_apt_service_key[0].arn
    } : {},
  )
}
