resource "aws_lb" "public" {
  name                       = "${local.name}-public"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.public_alb.id]
  subnets                    = values(aws_subnet.public)[*].id
  drop_invalid_header_fields = true
  enable_deletion_protection = true
}

resource "aws_lb" "admin" {
  name                       = "${local.name}-admin"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.admin_alb.id]
  subnets                    = values(aws_subnet.public)[*].id
  drop_invalid_header_fields = true
  enable_deletion_protection = true
}
