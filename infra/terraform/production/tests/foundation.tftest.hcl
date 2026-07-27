mock_provider "aws" {
  mock_data "aws_availability_zones" { defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] } }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

run "two_az_private_production_foundation" {
  command = plan
  variables {
    owner           = "platform"
    client_vpn_cidr = "10.90.0.0/22"
    operator_group_id = "operators"
    client_vpn_server_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/server"
    client_vpn_saml_provider_arn = "arn:aws:iam::123456789012:saml-provider/operators"
    public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/public"
    monthly_budget_usd        = 5000
    budget_notification_emails = ["ops@example.invalid"]
    alarm_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:alarms"
  }
  assert {
    condition     = length(aws_nat_gateway.this) == 2 && length(aws_subnet.application) == 2
    error_message = "Each AZ requires its own NAT and private application subnet."
  }
  assert {
    condition     = length(aws_vpc_endpoint.interface) == 7 && length(aws_grafana_workspace.this.network_access_control[0].vpce_ids) == 1
    error_message = "AWS APIs must use private endpoints and Grafana must accept only its workspace endpoint."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.grafana_endpoint_https.cidr_ipv4 == "10.90.0.0/22"
    error_message = "Grafana PrivateLink ingress must be limited to the Client VPN network."
  }
  assert {
    condition     = length(aws_db_instance.service) == 5 && alltrue([for db in aws_db_instance.service : db.multi_az && !db.publicly_accessible && db.deletion_protection])
    error_message = "Five databases must be Multi-AZ and private."
  }
  assert {
    condition     = aws_elasticache_replication_group.this.automatic_failover_enabled && aws_elasticache_replication_group.this.multi_az_enabled
    error_message = "Valkey failover and Multi-AZ are mandatory."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.public_https.from_port == 443 && aws_vpc_security_group_ingress_rule.operator_https.cidr_ipv4 == "10.90.0.0/22"
    error_message = "Only public HTTPS and VPN operator ingress are allowed."
  }
  assert {
    condition     = aws_ec2_client_vpn_endpoint.operator.split_tunnel && aws_ec2_client_vpn_authorization_rule.operator.access_group_id == "operators"
    error_message = "Client VPN must use split tunnel and operator group authorization."
  }
}
