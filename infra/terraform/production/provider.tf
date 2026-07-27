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
  database_names = toset(["property", "admin", "user", "ai", "coordinate"])
}
