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
