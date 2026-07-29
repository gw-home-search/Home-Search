provider "aws" {
  region = var.aws_region
  default_tags { tags = local.tags }
}

data "aws_caller_identity" "current" {}

locals {
  name = "home-search-budget-production"
  tags = {
    Project     = "home-search"
    Environment = "budget-production"
    Owner       = var.owner
    ManagedBy   = "terraform"
    DataClass   = "internal"
  }
  phase_order = {
    registry   = 0
    foundation = 1
    data       = 2
    private    = 3
    public     = 4
  }
  phase_index        = local.phase_order[var.deployment_phase]
  foundation_enabled = local.phase_index >= local.phase_order.foundation
  data_enabled       = local.phase_index >= local.phase_order.data
  private_enabled    = local.phase_index >= local.phase_order.private
  public_enabled     = local.phase_index >= local.phase_order.public
}

check "default_workspace_only" {
  assert {
    condition     = terraform.workspace == "default"
    error_message = "budget-production forbids Terraform workspaces; use only default."
  }
}

check "dns_is_last_and_explicit" {
  assert {
    condition     = !var.public_dns_enabled || local.public_enabled
    error_message = "public_dns_enabled requires deployment_phase=public."
  }
}
