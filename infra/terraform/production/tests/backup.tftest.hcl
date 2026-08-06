mock_provider "aws" {
  mock_data "aws_availability_zones" { defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] } }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

mock_provider "aws" { alias = "backup" }

variables {
  admin_certificate_arn    = "arn:aws:acm:ap-northeast-2:123456789012:certificate/admin"
  adot_collector_image_uri = "public.ecr.aws/aws-observability/aws-otel-collector@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  public_origin            = "https://home.example.invalid"
  image_uris = {
    property-api          = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    property-batch        = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-batch@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    property-flyway       = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-flyway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-api             = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-migration       = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-migration@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-ops             = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-ops@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-api              = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-insight-worker   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-insight-worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-flyway           = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-flyway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    source-data-migration = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/source-data-migration@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    public-gateway        = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/public-gateway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-gateway         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-gateway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    backup                = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/backup@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ops-bootstrap         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ops-bootstrap@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ml                    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ml@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ai                    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ai@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    chat-bff              = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/chat-bff@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    seo-renderer          = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/seo-renderer@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
run "five_database_backup_and_restore_testing" {
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
    deployment_release_tag            = "v1.2.3"
    migration_artifact_bucket         = "approved-migration-artifacts"
    migration_artifact_prefix         = "releases/v1.2.3/property-reference"
    migration_artifact_kms_key_arn    = "arn:aws:kms:ap-northeast-2:123456789012:key/source-artifact"
    migration_manifest_sha256         = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }

  assert {
    condition     = length(local.database_backup_resources) == 5
    error_message = "All five production databases must be selected for AWS Backup."
  }

  assert {
    condition = (
      aws_backup_plan.database.name == "home-search-production-database"
      && local.database_backup_retention.daily == 35
      && local.database_backup_retention.monthly == 365
    )
    error_message = "Database backups must retain daily recovery points for 35 days and monthly recovery points for 12 months."
  }

  assert {
    condition     = alltrue([for rule in aws_backup_plan.database.rule : length(rule.copy_action) == 1])
    error_message = "Every database backup tier must copy an encrypted recovery point to Tokyo."
  }

  assert {
    condition = (
      aws_backup_restore_testing_plan.monthly.schedule_expression_timezone == "Asia/Seoul"
      && aws_backup_restore_testing_selection.database.protected_resource_type == "RDS"
      && aws_backup_restore_testing_selection.database.validation_window_hours >= 24
      && contains(keys(aws_backup_restore_testing_selection.database.restore_metadata_overrides), "vpcSecurityGroupIds")
      && contains(keys(aws_backup_restore_testing_selection.database.restore_metadata_overrides), "dbSubnetGroupName")
    )
    error_message = "A monthly RDS restore test with an explicit validation window is mandatory."
  }

  assert {
    condition = (
      strcontains(aws_iam_role_policy_attachment.backup_backup.policy_arn, "AWSBackupServiceRolePolicyForBackup")
      && strcontains(aws_iam_role_policy_attachment.backup_restore.policy_arn, "AWSBackupServiceRolePolicyForRestores")
    )
    error_message = "The AWS Backup role needs the managed backup and restore service policies."
  }
}
