variable "project_name" {
  type    = string
  default = "home-search"
}
variable "owner" { type = string }
variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}
variable "backup_region" {
  type    = string
  default = "ap-northeast-1"
}
variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}
variable "client_vpn_cidr" {
  type = string
  validation {
    condition     = can(cidrhost(var.client_vpn_cidr, 0)) && var.client_vpn_cidr != "0.0.0.0/0"
    error_message = "client_vpn_cidr must be a bounded, non-public CIDR."
  }
}
variable "client_vpn_server_certificate_arn" { type = string }
variable "client_vpn_saml_provider_arn" { type = string }
variable "operator_group_id" { type = string }
variable "public_certificate_arn" { type = string }
variable "admin_certificate_arn" { type = string }
variable "public_origin" {
  type = string
  validation {
    condition     = can(regex("^https://[^/]+$", var.public_origin))
    error_message = "public_origin must be an HTTPS origin without a path."
  }
}
variable "service_activation_phase" {
  description = "Fail-closed service rollout phase: off, consumers, private, then all."
  type        = string
  default     = "off"
  validation {
    condition     = contains(["off", "consumers", "private", "all"], var.service_activation_phase)
    error_message = "service_activation_phase must be one of off, consumers, private, or all."
  }
}
variable "deployment_release_tag" {
  description = "Immutable release tag used to partition deployment evidence."
  type        = string
  validation {
    condition     = can(regex("^v[0-9]+[.][0-9]+[.][0-9]+$", var.deployment_release_tag))
    error_message = "deployment_release_tag must be an immutable semantic release tag."
  }
}
variable "migration_artifact_bucket" {
  description = "Approved versioned bucket containing the exported Property+Reference artifact."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.migration_artifact_bucket))
    error_message = "migration_artifact_bucket must be a valid S3 bucket name."
  }
}
variable "migration_artifact_prefix" {
  description = "Reviewed immutable object prefix containing exactly one data-only manifest."
  type        = string
  validation {
    condition = (
      can(regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,510}$", var.migration_artifact_prefix))
      && !strcontains("/${var.migration_artifact_prefix}/", "/../")
      && !strcontains("/${var.migration_artifact_prefix}/", "/./")
      && !strcontains(var.migration_artifact_prefix, "//")
    )
    error_message = "migration_artifact_prefix must be a bounded traversal-free S3 prefix."
  }
}
variable "migration_artifact_kms_key_arn" {
  description = "KMS key protecting the reviewed source migration artifact."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9A-Za-z-]+$", var.migration_artifact_kms_key_arn))
    error_message = "migration_artifact_kms_key_arn must be a KMS key ARN."
  }
}
variable "migration_manifest_sha256" {
  description = "Reviewed SHA-256 of the exact data-only manifest downloaded from the artifact prefix."
  type        = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.migration_manifest_sha256))
    error_message = "migration_manifest_sha256 must be a lower-case SHA-256 digest."
  }
}
variable "core_desired_count" {
  type    = number
  default = 2
  validation {
    condition     = var.core_desired_count >= 2 && floor(var.core_desired_count) == var.core_desired_count
    error_message = "core_desired_count must be an integer of at least two."
  }
}
variable "image_uris" {
  description = "The 17 release-manifest ECR image URIs pinned with @sha256 digests."
  type        = map(string)
  validation {
    condition = (
      length(var.image_uris) == 17
      && alltrue([for uri in values(var.image_uris) : can(regex("^[0-9]+[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$", uri))])
    )
    error_message = "image_uris must contain all 17 immutable ap-northeast-2 ECR digest URIs."
  }
}
variable "adot_collector_image_uri" {
  description = "Immutable ADOT collector image used by Prometheus-enabled ECS tasks."
  type        = string
  validation {
    condition = can(regex(
      "^(public[.]ecr[.]aws/aws-observability/aws-otel-collector|[0-9]+[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/aws-otel-collector)@sha256:[0-9a-f]{64}$",
      var.adot_collector_image_uri,
    ))
    error_message = "adot_collector_image_uri must be an immutable official ADOT or approved regional mirror digest URI."
  }
}
variable "monthly_budget_usd" {
  type = number
  validation {
    condition     = var.monthly_budget_usd > 0 && var.monthly_budget_usd <= 1000000000
    error_message = "monthly_budget_usd must be greater than zero and no more than one billion USD."
  }
}
variable "budget_notification_emails" {
  type = set(string)
  validation {
    condition = (
      length(var.budget_notification_emails) > 0
      && alltrue([for address in var.budget_notification_emails : can(regex("^[^@\\s]+@[^@\\s]+[.][^@\\s]+$", address))])
    )
    error_message = "budget_notification_emails must contain at least one valid email address."
  }
}
variable "alarm_topic_arn" { type = string }
variable "rds_instance_class" {
  type    = string
  default = "db.r7g.large"
}
variable "valkey_node_type" {
  type    = string
  default = "cache.r7g.large"
}
