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

variable "backup_schedules_enabled" {
  type        = bool
  default     = false
  description = "Enables daily DLM and logical backup schedules only after the approved DNS cutover."
}

variable "market_news_public_enabled" {
  type        = bool
  default     = false
  description = "Exposes the read-only market-news API after bootstrap and quality gates pass."
}

variable "market_news_schedules_enabled" {
  type        = bool
  default     = false
  description = "Reserved for a separately reviewed news reactivation; must remain false during RTMS stabilization."
}

variable "rtms_refresh_schedule_enabled" {
  type        = bool
  default     = false
  description = "Enables the guarded 04:30 KST RTMS orchestration independently from backup schedules."
}

variable "rtms_refresh_task_definition_arn" {
  type        = string
  default     = ""
  description = "Exact immutable RTMS refresh task definition revision used by EventBridge Scheduler."
  validation {
    condition = var.rtms_refresh_task_definition_arn == "" || can(regex(
      "^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-rtms-daily-refresh:[1-9][0-9]*$",
      var.rtms_refresh_task_definition_arn,
    ))
    error_message = "rtms_refresh_task_definition_arn must be empty or the exact budget RTMS refresh revision ARN."
  }
}

variable "prediction_enabled" {
  type        = bool
  default     = false
  description = "Enables the property prediction client only after ML artifact and health gates pass."
}

variable "ml_service_enabled" {
  type        = bool
  default     = false
  description = "Runs exactly one ML service task when the reviewed F37 model is installed."
}

variable "user_oauth_enabled_providers" {
  type        = set(string)
  default     = ["kakao"]
  description = "Exact allowlist of OAuth providers enabled in user-api and secret readiness."
  validation {
    condition = (
      length(var.user_oauth_enabled_providers) > 0
      && length(setsubtract(var.user_oauth_enabled_providers, toset(["google", "kakao", "naver"]))) == 0
    )
    error_message = "user_oauth_enabled_providers must contain only google, kakao, and naver."
  }
}

variable "alarm_email" {
  type        = string
  description = "Operator email subscribed to the budget-production SNS topic."
  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$", var.alarm_email))
    error_message = "alarm_email must be a valid email address."
  }
}

variable "cost_anomaly_monitor_arn" {
  type        = string
  default     = ""
  description = "Existing account-wide SERVICE dimensional anomaly monitor ARN referenced without taking ownership."
  validation {
    condition = (
      var.cost_anomaly_monitor_arn == ""
      || can(regex("^arn:aws:ce::[0-9]{12}:anomalymonitor/[0-9a-f-]{36}$", var.cost_anomaly_monitor_arn))
    )
    error_message = "cost_anomaly_monitor_arn must be empty before foundation or one exact account anomaly monitor ARN."
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
  description = "The existing 18 application images from one approved immutable release manifest."
  validation {
    condition = length(var.image_uris) == 0 || (
      length(var.image_uris) == 18
      && alltrue([for uri in values(var.image_uris) : can(regex("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$", uri))])
    )
    error_message = "image_uris must be empty before release selection or contain all 18 immutable Seoul ECR image URIs."
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

variable "platform_deployment_release_tag" {
  type        = string
  default     = ""
  description = "Optional live platform release tag preserved during an application-only incremental rollout."
  validation {
    condition     = var.platform_deployment_release_tag == "" || can(regex("^v[0-9]+[.][0-9]+[.][0-9]+$", var.platform_deployment_release_tag))
    error_message = "platform_deployment_release_tag must be empty or a canonical vMAJOR.MINOR.PATCH tag."
  }
}

variable "application_deployment_maximum_percents" {
  type        = map(number)
  default     = {}
  description = "Optional exact live ECS deployment maximum percentages preserved by incremental rollout."
  validation {
    condition = (
      length(setsubtract(toset(keys(var.application_deployment_maximum_percents)), toset([
        "admin-api", "admin-gateway", "ai", "chat-bff", "ml", "property-api", "public-gateway", "user-api",
      ]))) == 0
      && alltrue([for value in values(var.application_deployment_maximum_percents) : contains([100, 200], value)])
    )
    error_message = "application_deployment_maximum_percents may contain only known application services with values 100 or 200."
  }
}

variable "application_service_task_definition_arns" {
  type        = map(string)
  default     = {}
  description = "Exact live or release task definition ARN pins for the eight application ECS services."
  validation {
    condition = (
      length(var.application_service_task_definition_arns) == 0 || (
        toset(keys(var.application_service_task_definition_arns)) == toset([
          "admin-api", "admin-gateway", "ai", "chat-bff", "ml", "property-api", "public-gateway", "user-api",
        ])
        && alltrue([for service, arn in var.application_service_task_definition_arns :
          can(regex("^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-${service}:[1-9][0-9]*$", arn))
        ])
      )
    )
    error_message = "application_service_task_definition_arns must be empty or pin all eight application services to their exact budget-production family revisions."
  }
}

variable "application_service_desired_counts" {
  type        = map(number)
  default     = {}
  description = "Exact live desired-count pins for the eight application ECS services."
  validation {
    condition = (
      length(var.application_service_desired_counts) == 0 || (
        toset(keys(var.application_service_desired_counts)) == toset([
          "admin-api", "admin-gateway", "ai", "chat-bff", "ml", "property-api", "public-gateway", "user-api",
        ])
        && alltrue([for count in values(var.application_service_desired_counts) : count >= 0 && count == floor(count)])
      )
    )
    error_message = "application_service_desired_counts must be empty or pin all eight application services to non-negative integers."
  }
}

variable "scheduled_backup_task_definition_arn" {
  type        = string
  default     = ""
  description = "Optional exact live scheduled-backup task ARN preserved by incremental application rollouts."
  validation {
    condition = var.scheduled_backup_task_definition_arn == "" || can(regex(
      "^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-scheduled-backup:[0-9]+$",
      var.scheduled_backup_task_definition_arn,
    ))
    error_message = "scheduled_backup_task_definition_arn must be empty or the exact budget scheduled-backup revision ARN."
  }
}

variable "data_import_preserved_image_uri" {
  type        = string
  default     = ""
  description = "Optional live immutable data-import image preserved outside the incremental rollout."
  validation {
    condition = var.data_import_preserved_image_uri == "" || can(regex(
      "^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/backup@sha256:[0-9a-f]{64}$",
      var.data_import_preserved_image_uri,
    ))
    error_message = "data_import_preserved_image_uri must be empty or an immutable Seoul backup image URI."
  }
}

variable "data_import_preserved_release_tag" {
  type        = string
  default     = ""
  description = "Optional live data-import Release tag preserved outside the incremental rollout."
  validation {
    condition     = var.data_import_preserved_release_tag == "" || can(regex("^v[0-9]+[.][0-9]+[.][0-9]+$", var.data_import_preserved_release_tag))
    error_message = "data_import_preserved_release_tag must be empty or an immutable SemVer tag."
  }
}

variable "ai_supervisor_graph_mode" {
  type        = string
  default     = "off"
  description = "Exact live AI supervisor graph mode preserved during incremental rollout."
  validation {
    condition     = contains(["off", "shadow", "canary", "active"], var.ai_supervisor_graph_mode)
    error_message = "ai_supervisor_graph_mode must be off|shadow|canary|active."
  }
}

variable "ai_supervisor_graph_canary_percent" {
  type        = number
  default     = 0
  description = "Exact live AI supervisor graph canary percentage preserved during incremental rollout."
  validation {
    condition = (
      var.ai_supervisor_graph_canary_percent >= 0
      && var.ai_supervisor_graph_canary_percent <= 100
      && floor(var.ai_supervisor_graph_canary_percent) == var.ai_supervisor_graph_canary_percent
    )
    error_message = "ai_supervisor_graph_canary_percent must be an integer from 0 through 100."
  }
}

variable "property_migration_target" {
  type        = number
  default     = 41
  description = "Exact append-only Property Flyway target approved for the incremental rollout."
  validation {
    condition     = var.property_migration_target == 41
    error_message = "property_migration_target is pinned to 41 for this rollout."
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
