variable "aws_region" {
  description = "AWS region that owns the remote state resources."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket_name" {
  description = "Globally unique S3 bucket name for Terraform state."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid lower-case S3 bucket name."
  }
}

variable "state_key" {
  description = "Object key used after migrating this bootstrap state to S3."
  type        = string
  default     = "home-search/bootstrap/terraform.tfstate"
  validation {
    condition     = !startswith(var.state_key, "/") && endswith(var.state_key, ".tfstate")
    error_message = "state_key must be a relative .tfstate object key."
  }
}

variable "state_prefix" {
  description = "S3 object prefix reserved for all Home Search Terraform states."
  type        = string
  default     = "home-search"
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+$", var.state_prefix))
    error_message = "state_prefix must be one non-empty path segment."
  }
}

variable "staging_state_key" {
  description = "Exact remote state object key reserved for staging infrastructure."
  type        = string
  default     = "home-search/staging/terraform.tfstate"
  validation {
    condition     = startswith(var.staging_state_key, "home-search/staging/") && endswith(var.staging_state_key, ".tfstate")
    error_message = "staging_state_key must stay under home-search/staging and end in .tfstate."
  }
}

variable "github_repository" {
  description = "GitHub repository in owner/name form."
  type        = string
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must use owner/name form."
  }
}

variable "github_environment" {
  description = "Protected GitHub Environment allowed to assume the staging role."
  type        = string
  default     = "staging"
}

variable "github_workflow_name" {
  description = "Exact GitHub Actions workflow claim allowed to deploy staging."
  type        = string
  default     = "Deploy staging"
}

variable "github_staging_foundation_workflow_name" {
  description = "Exact GitHub Actions workflow claim allowed to plan and apply the staging foundation."
  type        = string
  default     = "Staging foundation"
}

variable "github_staging_foundation_allowed_refs" {
  description = "Protected refs allowed to plan and apply the staging foundation."
  type        = list(string)
  default     = ["refs/heads/main"]
  validation {
    condition = length(var.github_staging_foundation_allowed_refs) > 0 && alltrue([
      for ref in var.github_staging_foundation_allowed_refs : ref == "refs/heads/main"
    ])
    error_message = "Staging foundation OIDC may be used only from refs/heads/main."
  }
}

variable "allowed_refs" {
  description = "Git refs allowed to deploy through the protected staging environment."
  type        = list(string)
  default     = ["refs/heads/main", "refs/tags/v*"]
  validation {
    condition = length(var.allowed_refs) > 0 && alltrue([
      for ref in var.allowed_refs : startswith(ref, "refs/heads/") || startswith(ref, "refs/tags/")
    ])
    error_message = "allowed_refs must contain only branch or tag refs."
  }
}

variable "github_release_environment" {
  description = "Protected GitHub Environment allowed to publish immutable images."
  type        = string
  default     = "release"
}

variable "github_release_workflow_name" {
  type    = string
  default = "Publish release images"
}

variable "github_production_environment" {
  description = "Protected GitHub Environment required for production plan, apply, and rollback."
  type        = string
  default     = "production"
}

variable "github_production_workflow_name" {
  description = "Exact workflow claim allowed to plan and apply production."
  type        = string
  default     = "Deploy production"
}

variable "github_production_deploy_workflow_names" {
  description = "Exact rollback workflow claims allowed to update existing production ECS services."
  type        = list(string)
  default     = ["Roll back production application", "Roll back Supervisor Graph"]
}

variable "github_production_allowed_refs" {
  description = "Protected refs allowed to use production OIDC roles."
  type        = list(string)
  default     = ["refs/heads/main", "refs/tags/v*"]
  validation {
    condition = length(var.github_production_allowed_refs) > 0 && alltrue([
      for ref in var.github_production_allowed_refs : ref == "refs/heads/main" || startswith(ref, "refs/tags/v")
    ])
    error_message = "github_production_allowed_refs may contain only main or version tag refs."
  }
}
