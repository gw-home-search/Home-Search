variable "aws_region" {
  type        = string
  default     = "ap-northeast-2"
  description = "Fixed budget-production AWS region."
  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "budget-production is fixed to ap-northeast-2."
  }
}

variable "owner" {
  type        = string
  default     = "kwongwangjae"
  description = "Operational owner tag."
}

variable "deployment_phase" {
  type        = string
  default     = "registry"
  description = "Monotonic budget-production rollout phase."
  validation {
    condition     = contains(["registry", "foundation", "data", "private", "public"], var.deployment_phase)
    error_message = "deployment_phase must be registry|foundation|data|private|public."
  }
}

variable "ami_id" {
  type        = string
  description = "Exact reviewed ECS-optimized Amazon Linux 2023 AMI ID."
  validation {
    condition     = can(regex("^ami-[0-9a-f]{17}$", var.ami_id))
    error_message = "ami_id must be one exact 17-hex AMI ID from foundation evidence."
  }
}

variable "availability_zone" {
  type        = string
  description = "Pinned AZ selected by the t3a.large foundation preflight."
  validation {
    condition     = can(regex("^ap-northeast-2[a-d]$", var.availability_zone))
    error_message = "availability_zone must be one ap-northeast-2 AZ."
  }
}

variable "hosted_zone_id" {
  type        = string
  description = "Existing Route53 hosted zone ID for homesearch.world."
  validation {
    condition     = can(regex("^Z[A-Z0-9]{10,31}$", var.hosted_zone_id))
    error_message = "hosted_zone_id must be an existing Route53 zone ID."
  }
}

variable "public_hostname" {
  type        = string
  default     = "homesearch.world"
  description = "Single public FQDN; wildcard certificates are forbidden."
  validation {
    condition     = var.public_hostname == "homesearch.world"
    error_message = "budget-production public hostname is fixed to homesearch.world."
  }
}

variable "public_dns_enabled" {
  type        = bool
  default     = false
  description = "Creates the final A record only after a separate approved public-phase plan."
}

variable "data_services_enabled" {
  type        = bool
  default     = false
  description = "Starts PostgreSQL and Valkey only after the budget secret bootstrap and readiness checks pass."
}

variable "alarm_email" {
  type        = string
  description = "Operator email subscribed to the budget-production SNS topic."
  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$", var.alarm_email))
    error_message = "alarm_email must be a valid email address."
  }
}

variable "instance_type" {
  type        = string
  default     = "t3a.large"
  description = "Fixed single-node instance type."
  validation {
    condition     = var.instance_type == "t3a.large"
    error_message = "budget-production is costed and accepted only on t3a.large."
  }
}

variable "vpc_cidr" {
  type        = string
  default     = "10.44.0.0/24"
  description = "Fixed non-overlapping VPC CIDR."
}

variable "subnet_cidr" {
  type        = string
  default     = "10.44.0.0/26"
  description = "Single public subnet CIDR."
}

variable "root_volume_size_gib" {
  type        = number
  default     = 30
  description = "Root gp3 size."
  validation {
    condition     = var.root_volume_size_gib == 30
    error_message = "The reviewed root volume size is 30 GiB."
  }
}

variable "data_volume_size_gib" {
  type        = number
  default     = 80
  description = "Protected PostgreSQL/Valkey data gp3 size."
  validation {
    condition     = var.data_volume_size_gib >= 80
    error_message = "The data volume must be at least 80 GiB."
  }
}

variable "monthly_budget_usd" {
  type        = number
  default     = 100
  description = "Hard AWS monthly budget threshold in USD."
  validation {
    condition     = var.monthly_budget_usd == 100
    error_message = "The budget-production AWS budget is fixed to USD 100."
  }
}

variable "image_uris" {
  type        = map(string)
  default     = {}
  description = "The existing 17 application images from one approved immutable release manifest."
  validation {
    condition = length(var.image_uris) == 0 || (
      length(var.image_uris) == 17
      && alltrue([for uri in values(var.image_uris) : can(regex("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$", uri))])
    )
    error_message = "image_uris must be empty before release selection or contain all 17 immutable Seoul ECR image URIs."
  }
}

variable "platform_image_uris" {
  type        = map(string)
  default     = {}
  description = "The two budget platform images from the same approved release manifest."
  validation {
    condition = length(var.platform_image_uris) == 0 || (
      toset(keys(var.platform_image_uris)) == toset(["budget-postgres", "budget-valkey"])
      && alltrue([for uri in values(var.platform_image_uris) : can(regex("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/budget-(postgres|valkey)@sha256:[0-9a-f]{64}$", uri))])
    )
    error_message = "platform_image_uris must be empty before release selection or contain both immutable budget platform URIs."
  }
}

variable "deployment_release_tag" {
  type        = string
  default     = ""
  description = "Approved release tag recorded on task definitions and evidence."
  validation {
    condition     = var.deployment_release_tag == "" || can(regex("^v[0-9]+[.][0-9]+[.][0-9]+$", var.deployment_release_tag))
    error_message = "deployment_release_tag must be empty or a canonical vMAJOR.MINOR.PATCH tag."
  }
}

variable "migration_artifact_s3_uri" {
  type        = string
  default     = ""
  description = "Reviewed Property+Reference data-only artifact prefix used only by the one-shot import task."
  validation {
    condition     = var.migration_artifact_s3_uri == "" || can(regex("^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9][A-Za-z0-9._/-]*$", var.migration_artifact_s3_uri))
    error_message = "migration_artifact_s3_uri must be empty or an explicit non-root S3 prefix."
  }
}

variable "migration_manifest_sha256" {
  type        = string
  default     = ""
  description = "Reviewed SHA-256 for the selected data-only manifest."
  validation {
    condition     = var.migration_manifest_sha256 == "" || can(regex("^[0-9a-f]{64}$", var.migration_manifest_sha256))
    error_message = "migration_manifest_sha256 must be empty or one lowercase SHA-256."
  }
}
