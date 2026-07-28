resource "aws_kms_key" "data" {
  description             = "Home Search production data"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = local.data_kms_policy
}
resource "aws_kms_alias" "data" {
  name          = "alias/${local.name}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = local.name }
}
resource "aws_internet_gateway" "this" { vpc_id = aws_vpc.this.id }

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
}
resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_subnet" "public" {
  for_each                = { for index, az in local.azs : az => index }
  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, each.value)
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-public-${each.key}" }
}
resource "aws_subnet" "application" {
  for_each                = { for index, az in local.azs : az => index }
  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, each.value + 4)
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-app-${each.key}" }
}
resource "aws_subnet" "data" {
  for_each                = { for index, az in local.azs : az => index }
  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, each.value + 8)
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-data-${each.key}" }
}
resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}
resource "aws_eip" "nat" {
  for_each = aws_subnet.public
  domain   = "vpc"
}
resource "aws_nat_gateway" "this" {
  for_each      = aws_subnet.public
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = each.value.id
  depends_on    = [aws_internet_gateway.this]
}
resource "aws_route_table" "application" {
  for_each = aws_subnet.application
  vpc_id   = aws_vpc.this.id
}
resource "aws_route" "application_default" {
  for_each               = aws_route_table.application
  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[each.key].id
}
resource "aws_route_table_association" "application" {
  for_each       = aws_subnet.application
  subnet_id      = each.value.id
  route_table_id = aws_route_table.application[each.key].id
}

resource "aws_security_group" "public_alb" {
  name   = "${local.name}-public-alb"
  vpc_id = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "public_https" {
  security_group_id = aws_security_group.public_alb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_security_group" "operator" {
  name   = "${local.name}-operator"
  vpc_id = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "operator_https" {
  security_group_id = aws_security_group.operator.id
  cidr_ipv4         = var.client_vpn_cidr
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "operator_https" {
  security_group_id = aws_security_group.operator.id
  cidr_ipv4         = var.vpc_cidr
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_security_group" "database" {
  name   = "${local.name}-database"
  vpc_id = aws_vpc.this.id
}
resource "aws_security_group" "valkey" {
  name   = "${local.name}-valkey"
  vpc_id = aws_vpc.this.id
}
resource "aws_security_group" "endpoints" {
  name   = "${local.name}-endpoints"
  vpc_id = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "endpoint_https" {
  security_group_id = aws_security_group.endpoints.id
  cidr_ipv4         = var.vpc_cidr
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_security_group" "grafana_endpoint" {
  name   = "${local.name}-grafana-endpoint"
  vpc_id = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "grafana_endpoint_https" {
  security_group_id = aws_security_group.grafana_endpoint.id
  cidr_ipv4         = var.client_vpn_cidr
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_vpc_endpoint" "interface" {
  for_each = toset([
    "ecr.api", "ecr.dkr", "logs", "secretsmanager", "sts",
    "aps-workspaces", "grafana-workspace",
  ])
  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = values(aws_subnet.application)[*].id
  security_group_ids = each.key == "grafana-workspace" ? [
    aws_security_group.grafana_endpoint.id
  ] : [aws_security_group.endpoints.id]
}
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = values(aws_route_table.application)[*].id
}

resource "aws_ec2_client_vpn_endpoint" "operator" {
  description            = "SAML and IdP-MFA operator boundary"
  server_certificate_arn = var.client_vpn_server_certificate_arn
  client_cidr_block      = var.client_vpn_cidr
  split_tunnel           = true
  transport_protocol     = "udp"
  security_group_ids     = [aws_security_group.operator.id]
  vpc_id                 = aws_vpc.this.id
  authentication_options {
    type                           = "federated-authentication"
    saml_provider_arn              = var.client_vpn_saml_provider_arn
    self_service_saml_provider_arn = var.client_vpn_saml_provider_arn
  }
  connection_log_options {
    enabled              = true
    cloudwatch_log_group = aws_cloudwatch_log_group.vpn.name
  }
}
resource "aws_cloudwatch_log_group" "vpn" {
  name              = "/${var.project_name}/production/client-vpn"
  retention_in_days = 365
  kms_key_id        = aws_kms_key.data.arn
}
resource "aws_ec2_client_vpn_network_association" "operator" {
  for_each               = aws_subnet.application
  client_vpn_endpoint_id = aws_ec2_client_vpn_endpoint.operator.id
  subnet_id              = each.value.id
}
resource "aws_ec2_client_vpn_authorization_rule" "operator" {
  client_vpn_endpoint_id = aws_ec2_client_vpn_endpoint.operator.id
  target_network_cidr    = var.vpc_cidr
  access_group_id        = var.operator_group_id
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = values(aws_subnet.data)[*].id
}
resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-postgres17"
  family = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}
resource "aws_db_instance" "service" {
  for_each                        = local.database_names
  identifier                      = "${local.name}-${each.key}"
  engine                          = "postgres"
  engine_version                  = "17.10"
  db_name                         = each.value
  instance_class                  = var.rds_instance_class
  username                        = "cluster_admin"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.data.arn
  allocated_storage               = 100
  max_allocated_storage           = 1000
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  db_subnet_group_name            = aws_db_subnet_group.this.name
  parameter_group_name            = aws_db_parameter_group.postgres.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  publicly_accessible             = false
  multi_az                        = true
  deletion_protection             = true
  backup_retention_period         = 35
  copy_tags_to_snapshot           = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-${each.key}-final"
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  auto_minor_version_upgrade      = true
  apply_immediately               = false
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.data.arn
}

resource "aws_secretsmanager_secret" "container" {
  for_each                = local.secret_containers
  name                    = "${local.name}/${each.key}"
  description             = "Value is injected outside Terraform by the approved production bootstrap."
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
}
resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = values(aws_subnet.data)[*].id
}
resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = local.name
  description                = "Production Valkey"
  engine                     = "valkey"
  engine_version             = "8.1"
  node_type                  = var.valkey_node_type
  port                       = 6379
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.valkey.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  kms_key_id                 = aws_kms_key.data.arn
  snapshot_retention_limit   = 35
}

resource "aws_prometheus_workspace" "this" {
  alias = replace(local.name, "-", "_")
}
resource "aws_iam_role" "grafana" {
  name               = "${local.name}-grafana"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "grafana.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_grafana_workspace" "this" {
  name                     = local.name
  account_access_type      = "CURRENT_ACCOUNT"
  authentication_providers = ["SAML"]
  permission_type          = "CUSTOMER_MANAGED"
  role_arn                 = aws_iam_role.grafana.arn
  data_sources             = ["PROMETHEUS", "CLOUDWATCH"]
  network_access_control {
    prefix_list_ids = []
    vpce_ids        = [aws_vpc_endpoint.interface["grafana-workspace"].id]
  }
}

resource "aws_wafv2_web_acl" "public" {
  name  = local.name
  scope = "REGIONAL"
  default_action {
    allow {}
  }
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.name
    sampled_requests_enabled   = true
  }
  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 1
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "common"
      sampled_requests_enabled   = true
    }
  }
  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "known-bad"
      sampled_requests_enabled   = true
    }
  }
}

resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"
  dynamic "notification" {
    for_each = toset([50, 80, 100])
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "FORECASTED"
      subscriber_email_addresses = var.budget_notification_emails
    }
  }
}
