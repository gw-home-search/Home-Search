provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = var.project_name
      Environment = "staging"
      ManagedBy   = "terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

locals {
  name = "${var.project_name}-staging"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
  image_names = toset([
    "property-api", "property-batch", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
  ])
  service_log_names = toset([
    "property-api", "property-batch", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "restore-verification", "ml",
    "secret-bootstrap", "database-bootstrap", "runtime-grants",
  ])
  secret_containers = toset([
    "database-runtime", "database-bootstrap", "oauth-providers",
    "user-jwt", "admin-internal-jwt", "public-data-providers",
  ])
  task_security_group_names = toset([
    "public-gateway", "admin-gateway", "property", "admin", "user", "ops", "ml",
  ])
}
