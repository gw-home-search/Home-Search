output "network" {
  value = {
    vpc_id                  = aws_vpc.this.id
    public_subnet_ids       = values(aws_subnet.public)[*].id
    application_subnet_ids  = values(aws_subnet.application)[*].id
    data_subnet_ids         = values(aws_subnet.data)[*].id
    task_security_group_ids = { for name, group in aws_security_group.task : name => group.id }
  }
}

output "load_balancers" {
  value = {
    public_arn      = aws_lb.public.arn
    public_dns_name = aws_lb.public.dns_name
    admin_arn       = aws_lb.admin.arn
    admin_dns_name  = aws_lb.admin.dns_name
  }
}

output "data_endpoints" {
  value = {
    primary_rds_address           = aws_db_instance.primary.address
    coordinate_source_rds_address = aws_db_instance.coordinate_source.address
    redis_primary_endpoint        = aws_elasticache_replication_group.this.primary_endpoint_address
    ml_model_file_system_id       = aws_efs_file_system.ml_model.id
  }
}

output "logical_database_bootstrap_targets" {
  value = {
    primary           = ["home_search", "home_search_admin", "home_search_user"]
    coordinate_source = ["home_search_coordinate_source"]
  }
}

output "ecr_repository_urls" {
  value = { for name, repository in aws_ecr_repository.image : name => repository.repository_url }
}

output "secret_container_arns" {
  value = { for name, secret in aws_secretsmanager_secret.container : name => secret.arn }
}

output "workload_release" {
  value = {
    cluster_arn        = aws_ecs_cluster.this.arn
    service_names      = sort(keys(aws_ecs_service.service))
    one_shot_task_arns = { for name, task in aws_ecs_task_definition.one_shot : name => task.arn }
    service_task_arns  = { for name, task in aws_ecs_task_definition.service : name => task.arn }
    image_digests      = var.image_digests
  }
  description = "Non-secret deployment identities consumed by the release manifest."
}
