locals {
  budget_production_bootstrap_external_parameters = toset([
    "property/news/naver-client-id",
    "property/news/naver-client-secret",
  ])
}

resource "aws_ssm_parameter" "budget_production_external" {
  for_each         = local.budget_production_bootstrap_external_parameters
  name             = "/home-search/budget-production/${each.value}"
  description      = "Budget production protected value container; populated out-of-band before incremental rollout."
  type             = "SecureString"
  value_wo         = "UNSET"
  value_wo_version = 1

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Environment     = "budget-production"
    DataClass       = "secret"
    ParameterStatus = "out-of-band"
  }
}
