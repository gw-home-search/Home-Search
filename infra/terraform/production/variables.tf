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
variable "monthly_budget_usd" { type = number }
variable "budget_notification_emails" { type = set(string) }
variable "alarm_topic_arn" { type = string }
variable "rds_instance_class" {
  type    = string
  default = "db.r7g.large"
}
variable "valkey_node_type" {
  type    = string
  default = "cache.r7g.large"
}
