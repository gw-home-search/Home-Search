provider "aws" {
  region = var.aws_region
  default_tags { tags = local.tags }
}

provider "aws" {
  alias  = "backup"
  region = var.backup_region
  default_tags { tags = local.tags }
}

data "aws_availability_zones" "available" { state = "available" }
data "aws_caller_identity" "current" {}

locals {
  name = "${var.project_name}-production"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
  tags = {
    Project   = var.project_name, Environment = "production", Owner = var.owner,
    ManagedBy = "terraform", DataClass = "internal"
  }
  database_names = {
    property   = "home_search"
    admin      = "home_search_admin"
    user       = "home_search_user"
    ai         = "home_search_ai"
    coordinate = "home_search_coordinate_source"
  }
  data_kms_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountAdministration"
        Effect    = "Allow"
        Action    = "kms:*"
        Resource  = "*"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
      },
      {
        Sid    = "ProductionLogEncryption"
        Effect = "Allow"
        Action = [
          "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt",
          "kms:GenerateDataKey*", "kms:ReEncrypt*",
        ]
        Resource  = "*"
        Principal = { Service = "logs.${var.aws_region}.amazonaws.com" }
        Condition = {
          ArnLike = {
            "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/${var.project_name}/production/*"
          }
        }
      },
    ]
  })
  image_names = toset([
    "property-api", "property-batch", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
  ])
  service_names = toset([
    "property-api", "admin-api", "user-api", "public-gateway", "admin-gateway",
    "ml", "ai", "chat-bff", "user-insight-worker",
  ])
  one_shot_names = toset([
    "secret-bootstrap", "secret-readiness", "database-bootstrap", "property-flyway", "admin-migration", "user-flyway", "ai-migration", "source-data-migration",
    "runtime-grants", "property-batch", "map-marker-projection", "admin-ops", "backup",
  ])
  workload_names = setunion(local.service_names, local.one_shot_names)
  secret_containers = toset([
    "property-runtime-db", "admin-runtime-db", "user-runtime-db",
    "property-migrator-db", "admin-migrator-db", "user-migrator-db",
    "property-ai-reader-db", "coordinate-reader-db", "coordinate-migrator-db", "coordinate-importer-db",
    "ai-migrator-db", "ai-importer-db", "ai-runtime-db", "ai-runtime", "openai-provider", "oauth-providers",
    "user-jwt", "admin-internal-jwt", "admin-internal-jwt-public",
    "kakao-local-provider", "public-data-providers", "backup-db",
  ])
  task_security_group_names = toset([
    "public-gateway", "admin-gateway", "property", "admin", "user", "ml", "ai",
    "chat-bff", "user-insight-worker", "ops",
  ])
}
