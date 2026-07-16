provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "home-search"
      Environment = "bootstrap"
      ManagedBy   = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
