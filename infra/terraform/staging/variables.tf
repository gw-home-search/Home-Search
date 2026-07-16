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
