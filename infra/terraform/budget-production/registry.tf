locals {
  platform_repositories = toset(["budget-postgres", "budget-valkey"])
}

resource "aws_ecr_repository" "platform" {
  for_each             = local.platform_repositories
  name                 = "home-search/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  encryption_configuration { encryption_type = "AES256" }
  image_scanning_configuration { scan_on_push = true }
  lifecycle { prevent_destroy = true }
  tags = { Service = each.key, DataClass = "internal" }
}

resource "aws_ecr_lifecycle_policy" "platform" {
  for_each   = aws_ecr_repository.platform
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged platform layers after fourteen days"
      selection = {
        tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 14
      }
      action = { type = "expire" }
    }]
  })
}
