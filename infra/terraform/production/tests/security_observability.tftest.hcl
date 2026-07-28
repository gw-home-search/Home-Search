mock_provider "aws" {
  mock_data "aws_availability_zones" { defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] } }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

run "production_audit_and_grafana_boundary" {
  command = plan
  variables {
    owner                             = "platform"
    client_vpn_cidr                   = "10.90.0.0/22"
    operator_group_id                 = "operators"
    client_vpn_server_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/server"
    client_vpn_saml_provider_arn      = "arn:aws:iam::123456789012:saml-provider/operators"
    public_certificate_arn            = "arn:aws:acm:ap-northeast-2:123456789012:certificate/public"
    monthly_budget_usd                = 5000
    budget_notification_emails        = ["ops@example.invalid"]
    alarm_topic_arn                   = "arn:aws:sns:ap-northeast-2:123456789012:alarms"
  }

  assert {
    condition = (
      aws_cloudtrail.audit.is_multi_region_trail
      && aws_cloudtrail.audit.include_global_service_events
      && aws_cloudtrail.audit.enable_log_file_validation
    )
    error_message = "Production CloudTrail must cover all regions and validate log integrity."
  }

  assert {
    condition     = aws_guardduty_detector.this.enable
    error_message = "GuardDuty must be enabled in production."
  }

  assert {
    condition = (
      aws_config_configuration_recorder.this.recording_group[0].all_supported
      && aws_config_configuration_recorder_status.this.is_enabled
    )
    error_message = "AWS Config must continuously record all supported resources."
  }

  assert {
    condition = (
      aws_flow_log.vpc.traffic_type == "ALL"
      && aws_cloudwatch_log_group.vpc_flow.retention_in_days >= 365
    )
    error_message = "VPC Flow Logs must retain encrypted accepted and rejected traffic."
  }

  assert {
    condition = (
      aws_iam_role_policy.grafana.name == "read-amp-cloudwatch"
      && contains(local.grafana_amp_actions, "aps:QueryMetrics")
      && contains(local.grafana_cloudwatch_actions, "cloudwatch:GetMetricData")
    )
    error_message = "Grafana must receive explicit read-only AMP and CloudWatch permissions."
  }

  assert {
    condition = (
      aws_s3_bucket_public_access_block.audit.block_public_acls
      && aws_s3_bucket_public_access_block.audit.block_public_policy
      && aws_s3_bucket_versioning.audit.versioning_configuration[0].status == "Enabled"
    )
    error_message = "Audit storage must be versioned and fail closed against public access."
  }
}
