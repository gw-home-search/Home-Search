provider "aws" {
  region = var.aws_region
  default_tags {
    tags = local.required_tags
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

locals {
  name = "${var.project_name}-staging"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
  required_tags = {
    Project     = var.project_name
    Environment = "staging"
    Service     = "shared-platform"
    Owner       = var.owner
    ManagedBy   = "terraform"
    DataClass   = "internal"
  }
  image_names = toset([
    "property-api", "property-batch", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
  ])
  service_log_names = toset([
    "property-api", "property-batch", "property-event-relay", "property-event-maintenance", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "restore-verification", "ml",
    "secret-bootstrap", "database-bootstrap", "runtime-grants",
  ])
  secret_containers = toset([
    "database-runtime", "database-bootstrap", "oauth-providers",
    "user-jwt", "admin-internal-jwt", "admin-internal-jwt-public", "public-data-providers",
    "property-runtime-db", "property-ai-reader-db", "admin-runtime-db",
    "user-runtime-db", "coordinate-reader-db", "property-migrator-db",
    "admin-migrator-db", "user-migrator-db", "coordinate-migrator-db",
    "coordinate-importer-db", "backup-db",
  ])
  task_security_group_names = toset([
    "public-gateway", "admin-gateway", "property", "property-event-relay",
    "property-event-maintenance", "property-batch", "admin", "user", "user-insight-worker", "ops", "ml",
  ])
}
