provider "aws" {
  region = var.aws_region
  default_tags { tags = local.tags }
}

provider "aws" {
  alias  = "retained_ssm"
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "home-search"
      Environment = "budget-production"
    }
  }
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

check "data_services_require_data_phase" {
  assert {
    condition     = !var.data_services_enabled || local.data_enabled
    error_message = "data_services_enabled requires deployment_phase=data or later."
  }
}

check "private_phase_requires_ready_data_services" {
  assert {
    condition     = !local.private_enabled || var.data_services_enabled
    error_message = "private/public phases require the explicit post-secret-bootstrap data service gate."
  }
}

check "backup_schedules_are_post_cutover_only" {
  assert {
    condition     = !var.backup_schedules_enabled || (local.public_enabled && var.public_dns_enabled)
    error_message = "backup_schedules_enabled requires the public phase and explicit DNS cutover."
  }
}

check "market_news_schedules_are_post_cutover_only" {
  assert {
    condition     = !var.market_news_schedules_enabled || (local.public_enabled && var.data_services_enabled)
    error_message = "market_news_schedules_enabled requires the public phase and enabled data services."
  }
}

check "rtms_refresh_schedule_is_post_cutover_only" {
  assert {
    condition     = !var.rtms_refresh_schedule_enabled || (local.public_enabled && var.data_services_enabled)
    error_message = "rtms_refresh_schedule_enabled requires the public phase and enabled data services."
  }
}

check "prediction_requires_ml_service" {
  assert {
    condition     = !var.prediction_enabled || (local.public_enabled && var.ml_service_enabled)
    error_message = "prediction_enabled requires deployment_phase=public and ml_service_enabled=true."
  }
}

check "data_phase_requires_platform_release" {
  assert {
    condition     = !local.data_enabled || (length(var.platform_image_uris) == 2 && length(var.image_uris) == 18)
    error_message = "data phase requires both platform images and the exact 18-image release for reviewed one-shot tasks."
  }
}

check "private_phase_requires_application_release" {
  assert {
    condition     = !local.private_enabled || length(var.image_uris) == 18
    error_message = "private/public phases require the exact 18-image application release."
  }
}
