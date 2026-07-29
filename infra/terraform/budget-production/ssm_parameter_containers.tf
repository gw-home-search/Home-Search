locals {
  runtime_parameter_names = toset([
    "postgres/superuser-password",
    "postgres/property-runtime-password",
    "postgres/property-migrator-password",
    "postgres/property-importer-password",
    "postgres/user-runtime-password",
    "postgres/user-migrator-password",
    "postgres/admin-runtime-password",
    "postgres/admin-migrator-password",
    "postgres/ai-runtime-password",
    "postgres/backup-password",
    "valkey/property-password",
    "valkey/bff-password",
    "edge/certificate-passphrase",
    "user/oauth/kakao-client-id",
    "user/oauth/kakao-client-secret",
    "bff/openai-api-key",
  ])
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

  tags = { DataClass = "secret", ParameterStatus = "unconfigured" }
}
