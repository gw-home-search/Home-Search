resource "aws_security_group" "public_alb" {
  name        = "${local.name}-public-alb"
  description = "Public web ingress"
  vpc_id      = aws_vpc.this.id
  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description      = "HTTPS"
    from_port        = 443
    to_port          = 443
    protocol         = "tcp"
    cidr_blocks      = ["0.0.0.0/0"]
    ipv6_cidr_blocks = ["::/0"]
  }
  egress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [for subnet in aws_subnet.application : subnet.cidr_block]
  }
}

resource "aws_security_group" "admin_alb" {
  name        = "${local.name}-admin-alb"
  description = "CIDR restricted admin ingress"
  vpc_id      = aws_vpc.this.id
  ingress {
    description = "Admin HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = tolist(var.admin_allowed_cidrs)
  }
  egress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [for subnet in aws_subnet.application : subnet.cidr_block]
  }
}

resource "aws_security_group" "task" {
  for_each    = local.task_security_group_names
  name        = "${local.name}-${each.key}"
  description = "Private ECS task identity for ${each.key}"
  vpc_id      = aws_vpc.this.id
}

resource "aws_security_group" "runtime_endpoints" {
  name        = "${local.name}-runtime-endpoints"
  description = "Private AWS runtime endpoints from explicit ECS workloads"
  vpc_id      = aws_vpc.this.id
}

resource "aws_vpc_security_group_ingress_rule" "runtime_endpoints_from_tasks" {
  for_each                     = local.task_security_group_names
  security_group_id            = aws_security_group.runtime_endpoints.id
  referenced_security_group_id = aws_security_group.task[each.key].id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "AWS runtime HTTPS from ${each.key}"
}

resource "aws_vpc_security_group_egress_rule" "runtime_endpoints" {
  for_each                     = local.task_security_group_names
  security_group_id            = aws_security_group.task[each.key].id
  referenced_security_group_id = aws_security_group.runtime_endpoints.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "Private AWS runtime endpoints"
}

resource "aws_vpc_security_group_egress_rule" "s3" {
  for_each          = local.task_security_group_names
  security_group_id = aws_security_group.task[each.key].id
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "S3 gateway endpoint for ECR layers and scoped workload objects"
}

resource "aws_vpc_security_group_egress_rule" "dns_udp" {
  for_each          = local.task_security_group_names
  security_group_id = aws_security_group.task[each.key].id
  cidr_ipv4         = var.vpc_cidr
  from_port         = 53
  to_port           = 53
  ip_protocol       = "udp"
  description       = "VPC DNS"
}

resource "aws_vpc_security_group_egress_rule" "dns_tcp" {
  for_each          = local.task_security_group_names
  security_group_id = aws_security_group.task[each.key].id
  cidr_ipv4         = var.vpc_cidr
  from_port         = 53
  to_port           = 53
  ip_protocol       = "tcp"
  description       = "VPC DNS fallback"
}

resource "aws_vpc_security_group_egress_rule" "external_https" {
  for_each          = toset(["property-batch", "user"])
  security_group_id = aws_security_group.task[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "Approved external provider HTTPS through NAT"
}

locals {
  task_internal_egress = {
    public-gateway-property       = { source = "public-gateway", destination = "property", port = 8080 }
    public-gateway-user           = { source = "public-gateway", destination = "user", port = 8082 }
    admin-gateway-admin           = { source = "admin-gateway", destination = "admin", port = 8081 }
    admin-property                = { source = "admin", destination = "property", port = 8080 }
    property-db                   = { source = "property", destination = "database-primary", port = 5432 }
    property-coordinate           = { source = "property", destination = "database-coordinate", port = 5432 }
    property-redis                = { source = "property", destination = "redis", port = 6379 }
    property-ml                   = { source = "property", destination = "ml", port = 8001 }
    admin-db                      = { source = "admin", destination = "database-primary", port = 5432 }
    user-db                       = { source = "user", destination = "database-primary", port = 5432 }
    user-insight-worker-db        = { source = "user-insight-worker", destination = "database-primary", port = 5432 }
    user-insight-worker-msk       = { source = "user-insight-worker", destination = "streaming", port = 9098 }
    ops-db                        = { source = "ops", destination = "database-primary", port = 5432 }
    ops-coordinate                = { source = "ops", destination = "database-coordinate", port = 5432 }
    ops-redis                     = { source = "ops", destination = "redis", port = 6379 }
    property-event-relay-db       = { source = "property-event-relay", destination = "database-primary", port = 5432 }
    property-event-relay-msk      = { source = "property-event-relay", destination = "streaming", port = 9098 }
    property-event-maintenance-db = { source = "property-event-maintenance", destination = "database-primary", port = 5432 }
    property-batch-db             = { source = "property-batch", destination = "database-primary", port = 5432 }
    property-batch-coordinate     = { source = "property-batch", destination = "database-coordinate", port = 5432 }
    property-batch-redis          = { source = "property-batch", destination = "redis", port = 6379 }
    ml-efs                        = { source = "ml", destination = "efs", port = 2049 }
  }
  internal_destination_security_groups = {
    property            = aws_security_group.task["property"].id
    admin               = aws_security_group.task["admin"].id
    user                = aws_security_group.task["user"].id
    ml                  = aws_security_group.task["ml"].id
    database-primary    = aws_security_group.database_primary.id
    database-coordinate = aws_security_group.database_coordinate.id
    redis               = aws_security_group.redis.id
    streaming           = aws_security_group.streaming.id
    efs                 = aws_security_group.efs.id
  }
}

resource "aws_vpc_security_group_egress_rule" "internal" {
  for_each                     = local.task_internal_egress
  security_group_id            = aws_security_group.task[each.value.source].id
  referenced_security_group_id = local.internal_destination_security_groups[each.value.destination]
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
  description                  = "Allow ${each.key}"
}

resource "aws_vpc_security_group_ingress_rule" "public_gateway_from_alb" {
  security_group_id            = aws_security_group.task["public-gateway"].id
  referenced_security_group_id = aws_security_group.public_alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "admin_gateway_from_alb" {
  security_group_id            = aws_security_group.task["admin-gateway"].id
  referenced_security_group_id = aws_security_group.admin_alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "property_from_public_gateway" {
  security_group_id            = aws_security_group.task["property"].id
  referenced_security_group_id = aws_security_group.task["public-gateway"].id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "property_from_admin" {
  security_group_id            = aws_security_group.task["property"].id
  referenced_security_group_id = aws_security_group.task["admin"].id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "user_from_public_gateway" {
  security_group_id            = aws_security_group.task["user"].id
  referenced_security_group_id = aws_security_group.task["public-gateway"].id
  from_port                    = 8082
  to_port                      = 8082
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "admin_from_admin_gateway" {
  security_group_id            = aws_security_group.task["admin"].id
  referenced_security_group_id = aws_security_group.task["admin-gateway"].id
  from_port                    = 8081
  to_port                      = 8081
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ml_from_property" {
  security_group_id            = aws_security_group.task["ml"].id
  referenced_security_group_id = aws_security_group.task["property"].id
  from_port                    = 8001
  to_port                      = 8001
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "database_primary" {
  name        = "${local.name}-database-primary"
  description = "Primary PostgreSQL from owning ECS tasks only"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port = 5432
    to_port   = 5432
    protocol  = "tcp"
    security_groups = [
      aws_security_group.task["property"].id,
      aws_security_group.task["property-event-relay"].id,
      aws_security_group.task["property-event-maintenance"].id,
      aws_security_group.task["property-batch"].id,
      aws_security_group.task["admin"].id,
      aws_security_group.task["user"].id,
      aws_security_group.task["user-insight-worker"].id,
      aws_security_group.task["ops"].id,
    ]
  }
}

resource "aws_security_group" "database_coordinate" {
  name        = "${local.name}-database-coordinate"
  description = "Coordinate source PostgreSQL from property and ops tasks"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port = 5432
    to_port   = 5432
    protocol  = "tcp"
    security_groups = [
      aws_security_group.task["property"].id,
      aws_security_group.task["property-batch"].id,
      aws_security_group.task["ops"].id,
    ]
  }
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "TLS Redis from ECS tasks only"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port = 6379
    to_port   = 6379
    protocol  = "tcp"
    security_groups = [
      aws_security_group.task["property"].id,
      aws_security_group.task["property-batch"].id,
      aws_security_group.task["ops"].id,
    ]
  }
}

resource "aws_security_group" "efs" {
  name        = "${local.name}-efs"
  description = "ML model EFS from ECS tasks only"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [aws_security_group.task["ml"].id]
  }
}
