mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c", "ap-northeast-2b"] }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform-test"
      user_id    = "AIDATEST"
    }
  }
}

run "workload_egress_is_private_and_allowlisted" {
  command = plan
  variables {
    admin_allowed_cidrs    = ["203.0.113.10/32"]
    public_origin          = "https://staging.example.test"
    admin_origin           = "https://admin.staging.example.test"
    public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      length(aws_vpc_security_group_egress_rule.runtime_endpoints) == length(local.task_security_group_names),
      length(aws_vpc_security_group_egress_rule.s3) == length(local.task_security_group_names),
      length(aws_vpc_security_group_egress_rule.dns_udp) == length(local.task_security_group_names),
      length(aws_vpc_security_group_egress_rule.dns_tcp) == length(local.task_security_group_names),
    ])
    error_message = "Every ECS task SG must receive only explicit runtime endpoint, S3, and DNS baseline egress."
  }

  assert {
    condition = alltrue([
      toset(keys(aws_vpc_endpoint.interface)) == toset([
        "ecr.api", "ecr.dkr", "kms", "logs", "secretsmanager", "sts",
      ]),
      aws_vpc_endpoint.s3.vpc_endpoint_type == "Gateway",
      length(aws_vpc_endpoint.s3.route_table_ids) == 1,
    ])
    error_message = "AWS runtime dependencies must use private interface endpoints and an S3 gateway endpoint."
  }

  assert {
    condition = toset(keys(aws_vpc_security_group_egress_rule.external_https)) == toset([
      "property-batch", "user", "ai",
      ]) && alltrue([
      for rule in aws_vpc_security_group_egress_rule.external_https :
      rule.from_port == 443 && rule.to_port == 443 && rule.cidr_ipv4 == "0.0.0.0/0"
    ])
    error_message = "Only provider batch, OAuth user, and AI provider workloads may use NAT HTTPS egress."
  }

  assert {
    condition = alltrue([
      aws_vpc_security_group_egress_rule.internal["property-event-relay-msk"].from_port == 9098,
      aws_vpc_security_group_egress_rule.internal["property-event-maintenance-db"].from_port == 5432,
      aws_vpc_security_group_egress_rule.internal["public-gateway-property"].from_port == 8080,
      aws_vpc_security_group_egress_rule.internal["public-gateway-user"].from_port == 8082,
    ])
    error_message = "Internal egress must remain explicit by source workload, destination SG, and port."
  }
}
