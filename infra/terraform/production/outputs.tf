output "amp_workspace_id" { value = aws_prometheus_workspace.this.id }
output "grafana_workspace_id" { value = aws_grafana_workspace.this.id }
output "client_vpn_endpoint_id" { value = aws_ec2_client_vpn_endpoint.operator.id }
output "audit_bucket_name" { value = aws_s3_bucket.audit.id }
output "cloudtrail_arn" { value = aws_cloudtrail.audit.arn }
output "guardduty_detector_id" { value = aws_guardduty_detector.this.id }
output "vpc_flow_log_id" { value = aws_flow_log.vpc.id }
output "backup_vault_arns" {
  value = {
    primary = aws_backup_vault.primary.arn
    copy    = aws_backup_vault.copy.arn
  }
}
output "restore_testing_plan_arn" { value = aws_backup_restore_testing_plan.monthly.arn }
output "database_secret_arns" {
  value     = { for name, db in aws_db_instance.service : name => db.master_user_secret[0].secret_arn }
  sensitive = true
}
