output "amp_workspace_id" { value = aws_prometheus_workspace.this.id }
output "grafana_workspace_id" { value = aws_grafana_workspace.this.id }
output "client_vpn_endpoint_id" { value = aws_ec2_client_vpn_endpoint.operator.id }
output "database_secret_arns" {
  value     = { for name, db in aws_db_instance.service : name => db.master_user_secret[0].secret_arn }
  sensitive = true
}
