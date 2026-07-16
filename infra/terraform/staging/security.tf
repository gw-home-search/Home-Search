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
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
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
      aws_security_group.task["admin"].id,
      aws_security_group.task["user"].id,
      aws_security_group.task["ops"].id,
    ]
  }
}

resource "aws_security_group" "database_coordinate" {
  name        = "${local.name}-database-coordinate"
  description = "Coordinate source PostgreSQL from property and ops tasks"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.task["property"].id, aws_security_group.task["ops"].id]
  }
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "TLS Redis from ECS tasks only"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.task["property"].id]
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
