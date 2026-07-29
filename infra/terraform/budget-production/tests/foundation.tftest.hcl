mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

variables {
  ami_id            = "ami-0123456789abcdef0"
  availability_zone = "ap-northeast-2a"
  hosted_zone_id    = "Z0123456789ABCDEFG"
  alarm_email       = "operator@example.com"
}

run "registry_phase_owns_only_budget_platform_repositories" {
  command = plan
  variables { deployment_phase = "registry" }

  assert {
    condition = (
      toset(keys(aws_ecr_repository.platform)) == toset(["budget-postgres", "budget-valkey"])
      && length(aws_instance.host) == 0
      && length(aws_ebs_volume.data) == 0
      && length(aws_ecs_cluster.this) == 0
    )
    error_message = "Registry phase must create only the two budget platform ECR repositories."
  }
}

run "foundation_is_single_az_single_instance_and_data_safe" {
  command = plan
  variables { deployment_phase = "foundation" }

  assert {
    condition = (
      length(aws_instance.host) == 1
      && aws_instance.host[0].instance_type == "t3a.large"
      && aws_instance.host[0].availability_zone == "ap-northeast-2a"
      && aws_instance.host[0].metadata_options[0].http_tokens == "required"
      && aws_instance.host[0].disable_api_termination
      && aws_instance.host[0].instance_initiated_shutdown_behavior == "stop"
    )
    error_message = "Foundation must use one protected t3a.large with IMDSv2 in the pinned AZ."
  }

  assert {
    condition = (
      aws_ebs_volume.data[0].size == 80
      && aws_ebs_volume.data[0].type == "gp3"
      && aws_ebs_volume.data[0].iops == 3000
      && aws_ebs_volume.data[0].throughput == 125
      && aws_ebs_volume.data[0].encrypted
      && !aws_volume_attachment.data[0].force_detach
    )
    error_message = "Data EBS must be encrypted 80 GiB gp3 and never force-detached."
  }

  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_ingress_rule.public : rule.from_port]) == toset([80, 443])
      && alltrue([for rule in aws_vpc_security_group_ingress_rule.public : rule.to_port == rule.from_port])
    )
    error_message = "Only HTTP/HTTPS may enter the host."
  }
}

run "public_dns_is_an_explicit_last_step" {
  command = plan
  variables {
    deployment_phase   = "public"
    public_dns_enabled = false
  }

  assert {
    condition     = length(aws_route53_record.public) == 0
    error_message = "Public phase must keep DNS disabled until a separate approved apply."
  }
}
