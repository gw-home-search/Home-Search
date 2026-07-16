variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project_name" {
  type    = string
  default = "home-search"
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
      "user-api", "user-flyway", "source-data-migration",
      "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
      ]))) == 0 && length(setsubtract(toset([
      "property-api", "property-batch", "property-flyway",
      "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-flyway", "source-data-migration",
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
