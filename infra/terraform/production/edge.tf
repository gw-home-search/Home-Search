resource "aws_lb" "public" {
  name                       = "${local.name}-public"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.public_alb.id]
  subnets                    = values(aws_subnet.public)[*].id
  drop_invalid_header_fields = true
  enable_deletion_protection = true
  idle_timeout               = 120
}

resource "aws_lb" "admin" {
  name                       = "${local.name}-admin"
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.admin_alb.id]
  subnets                    = values(aws_subnet.application)[*].id
  drop_invalid_header_fields = true
  enable_deletion_protection = true
  idle_timeout               = 120
}

resource "aws_lb_target_group" "gateway" {
  for_each             = toset(["public-gateway", "admin-gateway"])
  name                 = "${local.name}-${each.key == "public-gateway" ? "public" : "admin"}-gw"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.this.id
  deregistration_delay = 120
  health_check {
    enabled             = true
    path                = "/"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 5
    matcher             = "200-399"
  }
}

resource "aws_lb_listener" "public_https" {
  load_balancer_arn = aws_lb.public.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.public_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway["public-gateway"].arn
  }
}

resource "aws_lb_listener" "admin_https" {
  load_balancer_arn = aws_lb.admin.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.admin_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway["admin-gateway"].arn
  }
}

resource "aws_wafv2_web_acl_association" "public" {
  resource_arn = aws_lb.public.arn
  web_acl_arn  = aws_wafv2_web_acl.public.arn
}
