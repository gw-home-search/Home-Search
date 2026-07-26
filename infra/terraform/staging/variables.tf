variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project_name" {
  type    = string
  default = "home-search"
}

variable "owner" {
  description = "Accountable team or operator recorded on every taggable staging resource."
  type        = string
  default     = "home-search-platform"
  validation {
    condition     = trimspace(var.owner) != ""
    error_message = "owner must be a non-empty team or operator identifier."
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "admin_allowed_cidrs" {
  description = "Explicit operator CIDRs allowed to reach the admin ALB."
  type        = set(string)
  validation {
    condition = length(var.admin_allowed_cidrs) > 0 && alltrue([
      for cidr in var.admin_allowed_cidrs : can(cidrhost(cidr, 0)) && !contains(["0.0.0.0/0", "::/0"], cidr)
    ])
    error_message = "admin_allowed_cidrs must be non-empty valid CIDRs and cannot allow the whole internet."
  }
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "rds_connection_alarm_threshold" {
  description = "Approved staging connection count approximating 80 percent of max_connections for the selected RDS class."
  type        = number
  default     = 70
  validation {
    condition     = var.rds_connection_alarm_threshold > 0
    error_message = "rds_connection_alarm_threshold must be positive."
  }
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.micro"
}

variable "log_retention_days" {
  type    = number
  default = 30
  validation {
    condition     = var.log_retention_days >= 30
    error_message = "Staging logs must be retained for at least 30 days."
  }
}

variable "image_digests" {
  description = "Release-manifest SHA-256 digest for every workload image. Mutable tags are rejected."
  type        = map(string)
  validation {
    condition = length(setsubtract(toset(keys(var.image_digests)), toset([
      "property-api", "property-batch", "property-flyway",
      "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
      "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
      ]))) == 0 && length(setsubtract(toset([
      "property-api", "property-batch", "property-flyway",
      "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
      "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
      ]), toset(keys(var.image_digests)))) == 0 && alltrue([
      for digest in values(var.image_digests) : can(regex("^sha256:[0-9a-f]{64}$", digest))
    ])
    error_message = "image_digests must contain every image exactly once as a sha256 digest from the release manifest."
  }
}

variable "public_certificate_arn" {
  description = "Validated ACM certificate for the public staging domain."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:acm:[^:]+:[0-9]{12}:certificate/.+$", var.public_certificate_arn))
    error_message = "public_certificate_arn must be an ACM certificate ARN."
  }
}

variable "admin_certificate_arn" {
  description = "Validated ACM certificate for the CIDR-restricted admin staging domain."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:acm:[^:]+:[0-9]{12}:certificate/.+$", var.admin_certificate_arn))
    error_message = "admin_certificate_arn must be an ACM certificate ARN."
  }
}

variable "public_origin" {
  type = string
  validation {
    condition     = can(regex("^https://[^/]+$", var.public_origin))
    error_message = "public_origin must be an HTTPS origin without a path."
  }
}

variable "admin_origin" {
  type = string
  validation {
    condition     = can(regex("^https://[^/]+$", var.admin_origin))
    error_message = "admin_origin must be an HTTPS origin without a path."
  }
}

variable "enable_ml" {
  description = "Creates the optional ML ECS service; the task always fails fast when /model is empty."
  type        = bool
  default     = false
}

variable "enable_services" {
  description = "Starts long-running ECS services only after bootstrap and migration tasks have succeeded."
  type        = bool
  default     = false
}

variable "enable_backup_schedules" {
  description = "Enables backup schedules only after secret bootstrap, database migrations, and runtime grants succeed."
  type        = bool
  default     = false
}

variable "enable_property_event_relay_schedule" {
  description = "Enables the five-minute property outbox relay only after MSK topics and the property database are ready."
  type        = bool
  default     = false
}

variable "enable_property_event_retention_schedule" {
  description = "Enables the daily published outbox cleanup only after V26 and runtime grants are applied."
  type        = bool
  default     = false
}

variable "enable_market_news_schedules" {
  description = "Enables market news collection schedules only after provider terms, credentials, migrations, and quality gates are ready."
  type        = bool
  default     = false
}

variable "enable_market_news_public" {
  description = "Enables the public market news API independently from collection schedules after a reviewed publication exists."
  type        = bool
  default     = false
}

variable "enable_user_insights_public" {
  description = "Exposes subscription and inbox controllers only after authenticated staging E2E approval."
  type        = bool
  default     = false
}
