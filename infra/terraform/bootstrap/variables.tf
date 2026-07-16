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
