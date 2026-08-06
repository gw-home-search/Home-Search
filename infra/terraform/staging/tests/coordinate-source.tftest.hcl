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

variables {
  admin_allowed_cidrs    = ["203.0.113.10/32"]
  public_origin          = "https://staging.example.test"
  admin_origin           = "https://admin.staging.example.test"
  public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
  admin_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
  image_digests = { for name in [
    "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff", "seo-renderer",
  ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
}

run "coordinate_source_runtime_is_denied_by_default" {
  command = plan

  assert {
    condition = alltrue([
      !contains(local.workload_execution_secret_names["property-api"], "coordinate-reader-db"),
      !contains(local.workload_execution_secret_names["property-batch"], "coordinate-reader-db"),
      !contains(keys(local.task_internal_egress), "property-coordinate"),
      !contains(keys(local.task_internal_egress), "property-batch-coordinate"),
      contains(keys(local.task_internal_egress), "ops-coordinate"),
      local.coordinate_source_ingress_sources == ["ops"],
    ])
    error_message = "Coordinate source must be unreachable from Property workloads before operator activation."
  }
}

run "reviewed_operator_activation_restores_read_only_runtime_path" {
  command = plan
  variables {
    enable_coordinate_source_runtime = true
  }

  assert {
    condition = alltrue([
      contains(local.workload_execution_secret_names["property-api"], "coordinate-reader-db"),
      contains(local.workload_execution_secret_names["property-batch"], "coordinate-reader-db"),
      contains(keys(local.task_internal_egress), "property-coordinate"),
      contains(keys(local.task_internal_egress), "property-batch-coordinate"),
      one([for item in local.coordinate_source_environment : item.value if item.name == "COORDINATE_SOURCE_DB_READ_ONLY"]) == "true",
      local.coordinate_source_ingress_sources == ["ops", "property", "property-batch"],
    ])
    error_message = "The explicit activation path must restore only the approved read-only coordinate runtime boundary."
  }
}
