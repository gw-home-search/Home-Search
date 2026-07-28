resource "aws_security_group" "admin_alb" {
  name        = "${local.name}-admin-alb"
  description = "VPN-only internal Admin ALB"
  vpc_id      = aws_vpc.this.id
}

resource "aws_vpc_security_group_ingress_rule" "admin_alb_https" {
  security_group_id = aws_security_group.admin_alb.id
  cidr_ipv4         = var.client_vpn_cidr
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_security_group" "task" {
  for_each    = local.task_security_group_names
  name        = "${local.name}-${each.key}"
  description = "Private ECS identity for ${each.key}"
  vpc_id      = aws_vpc.this.id
}

resource "aws_security_group" "streaming" {
  name        = "${local.name}-streaming"
  description = "MSK IAM ingress from approved workers"
  vpc_id      = aws_vpc.this.id
}

resource "aws_security_group" "efs" {
  name        = "${local.name}-efs"
  description = "ML model EFS ingress"
  vpc_id      = aws_vpc.this.id
}

resource "aws_vpc_security_group_egress_rule" "public_alb_gateway" {
  security_group_id            = aws_security_group.public_alb.id
  referenced_security_group_id = aws_security_group.task["public-gateway"].id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "admin_alb_gateway" {
  security_group_id            = aws_security_group.admin_alb.id
  referenced_security_group_id = aws_security_group.task["admin-gateway"].id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

locals {
  task_ingress = {
    public-gateway   = { destination = "public-gateway", source_sg = aws_security_group.public_alb.id, port = 8080 }
    admin-gateway    = { destination = "admin-gateway", source_sg = aws_security_group.admin_alb.id, port = 8080 }
    gateway-property = { destination = "property", source_sg = aws_security_group.task["public-gateway"].id, port = 8080 }
    gateway-user     = { destination = "user", source_sg = aws_security_group.task["public-gateway"].id, port = 8082 }
    gateway-chat     = { destination = "chat-bff", source_sg = aws_security_group.task["public-gateway"].id, port = 8083 }
    admin-api        = { destination = "admin", source_sg = aws_security_group.task["admin-gateway"].id, port = 8081 }
    admin-property   = { destination = "property", source_sg = aws_security_group.task["admin"].id, port = 8080 }
    chat-ai          = { destination = "ai", source_sg = aws_security_group.task["chat-bff"].id, port = 8000 }
    property-ml      = { destination = "ml", source_sg = aws_security_group.task["property"].id, port = 8001 }
  }
  task_egress = {
    public-property = { source = "public-gateway", destination_sg = aws_security_group.task["property"].id, port = 8080 }
    public-user     = { source = "public-gateway", destination_sg = aws_security_group.task["user"].id, port = 8082 }
    public-chat     = { source = "public-gateway", destination_sg = aws_security_group.task["chat-bff"].id, port = 8083 }
    admin-api       = { source = "admin-gateway", destination_sg = aws_security_group.task["admin"].id, port = 8081 }
    admin-property  = { source = "admin", destination_sg = aws_security_group.task["property"].id, port = 8080 }
    chat-ai         = { source = "chat-bff", destination_sg = aws_security_group.task["ai"].id, port = 8000 }
    property-ml     = { source = "property", destination_sg = aws_security_group.task["ml"].id, port = 8001 }
    worker-msk      = { source = "user-insight-worker", destination_sg = aws_security_group.streaming.id, port = 9098 }
    ml-efs          = { source = "ml", destination_sg = aws_security_group.efs.id, port = 2049 }
  }
  database_clients       = toset(["property", "admin", "user", "ai", "user-insight-worker", "ops"])
  valkey_clients         = toset(["property", "chat-bff", "ops"])
  external_https_clients = toset(["property", "user", "ai", "ops"])
}

resource "aws_vpc_security_group_ingress_rule" "task" {
  for_each                     = local.task_ingress
  security_group_id            = aws_security_group.task[each.value.destination].id
  referenced_security_group_id = each.value.source_sg
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "task_internal" {
  for_each                     = local.task_egress
  security_group_id            = aws_security_group.task[each.value.source].id
  referenced_security_group_id = each.value.destination_sg
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "database" {
  for_each                     = local.database_clients
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.task[each.key].id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "database" {
  for_each                     = local.database_clients
  security_group_id            = aws_security_group.task[each.key].id
  referenced_security_group_id = aws_security_group.database.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "valkey" {
  for_each                     = local.valkey_clients
  security_group_id            = aws_security_group.valkey.id
  referenced_security_group_id = aws_security_group.task[each.key].id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "valkey" {
  for_each                     = local.valkey_clients
  security_group_id            = aws_security_group.task[each.key].id
  referenced_security_group_id = aws_security_group.valkey.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "streaming" {
  security_group_id            = aws_security_group.streaming.id
  referenced_security_group_id = aws_security_group.task["user-insight-worker"].id
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "efs" {
  security_group_id            = aws_security_group.efs.id
  referenced_security_group_id = aws_security_group.task["ml"].id
  from_port                    = 2049
  to_port                      = 2049
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "runtime_endpoint" {
  for_each                     = local.task_security_group_names
  security_group_id            = aws_security_group.task[each.key].id
  referenced_security_group_id = aws_security_group.endpoints.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "s3" {
  for_each          = local.task_security_group_names
  security_group_id = aws_security_group.task[each.key].id
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "dns_udp" {
  for_each          = local.task_security_group_names
  security_group_id = aws_security_group.task[each.key].id
  cidr_ipv4         = var.vpc_cidr
  from_port         = 53
  to_port           = 53
  ip_protocol       = "udp"
}

resource "aws_vpc_security_group_egress_rule" "dns_tcp" {
  for_each          = local.task_security_group_names
  security_group_id = aws_security_group.task[each.key].id
  cidr_ipv4         = var.vpc_cidr
  from_port         = 53
  to_port           = 53
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "provider_https" {
  for_each          = local.external_https_clients
  security_group_id = aws_security_group.task[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
