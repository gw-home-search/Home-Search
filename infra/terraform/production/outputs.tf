output "amp_workspace_id" { value = aws_prometheus_workspace.this.id }
output "grafana_workspace_id" { value = aws_grafana_workspace.this.id }
output "client_vpn_endpoint_id" { value = aws_ec2_client_vpn_endpoint.operator.id }
output "audit_bucket_name" { value = aws_s3_bucket.audit.id }
output "reference_raw_bucket_name" { value = aws_s3_bucket.reference_raw.id }
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
output "cost_anomaly_subscription_arn" { value = aws_ce_anomaly_subscription.daily.arn }
output "database_secret_arns" {
  value     = { for name, db in aws_db_instance.service : name => db.master_user_secret[0].secret_arn }
  sensitive = true
}
output "ecs_cluster" {
  value = {
    arn  = aws_ecs_cluster.this.arn
    name = aws_ecs_cluster.this.name
  }
}
output "application_subnet_ids" { value = values(aws_subnet.application)[*].id }
output "ops_security_group_id" { value = aws_security_group.task["ops"].id }
output "core_desired_count" { value = var.core_desired_count }
output "service_task_definition_arns" {
  value = { for name, definition in aws_ecs_task_definition.service : name => definition.arn }
}
output "one_shot_task_definition_arns" {
  value = { for name, definition in aws_ecs_task_definition.one_shot : name => definition.arn }
}
output "load_balancer_dns_names" {
  value = {
    public = aws_lb.public.dns_name
    admin  = aws_lb.admin.dns_name
  }
}
