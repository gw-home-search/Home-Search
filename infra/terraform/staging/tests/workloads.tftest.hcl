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

run "digest_pinned_private_rollback_capable_workloads" {
  command = plan
  variables {
    admin_allowed_cidrs    = ["203.0.113.10/32"]
    public_origin          = "https://staging.example.test"
    admin_origin           = "https://admin.staging.example.test"
    public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    enable_services        = true
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      for digest in values(var.image_digests) : can(regex("^sha256:[0-9a-f]{64}$", digest))
    ]) && length(var.image_digests) == 14
    error_message = "Every service and one-shot task image must be immutable and digest pinned."
  }

  assert {
    condition = length(setsubtract(toset(keys(aws_ecs_service.service)), toset([
      "property-api", "admin-api", "user-api", "public-gateway", "admin-gateway",
    ]))) == 0 && length(keys(aws_ecs_service.service)) == 5
    error_message = "Only long-running workloads may be ECS services when optional ML is disabled."
  }

  assert {
    condition = alltrue([
      for service in aws_ecs_service.service :
      service.deployment_circuit_breaker[0].enable && service.deployment_circuit_breaker[0].rollback &&
      !service.network_configuration[0].assign_public_ip
    ])
    error_message = "Every ECS service must be private and automatically roll back failed deployments."
  }

  assert {
    condition = length(setsubtract(toset(keys(aws_ecs_task_definition.one_shot)), toset([
      "secret-bootstrap", "database-bootstrap", "property-flyway", "admin-migration",
      "user-flyway", "source-data-migration", "runtime-grants", "property-batch", "admin-ops", "backup",
    ]))) == 0 && length(keys(aws_ecs_task_definition.one_shot)) == 10
    error_message = "Bootstrap, migrations, batch, ops, and backup must remain one-shot task definitions."
  }

  assert {
    condition = one([
      for volume in aws_ecs_task_definition.service["ml"].volume :
      volume.efs_volume_configuration[0].transit_encryption if volume.name == "model"
    ]) == "ENABLED"
    error_message = "The ML model must mount encrypted EFS with transit encryption enabled."
  }

  assert {
    condition = alltrue([
      aws_iam_role.secret_bootstrap_task.name != aws_iam_role.runtime_task.name,
      aws_iam_role.database_bootstrap_task.name != aws_iam_role.runtime_task.name,
      local.one_shot_specs["secret-bootstrap"].command == ["secret-bootstrap"],
      local.one_shot_specs["database-bootstrap"].command == ["db-bootstrap"],
    ])
    error_message = "Secret and database bootstraps must use distinct roles and explicit idempotent modes."
  }
}
