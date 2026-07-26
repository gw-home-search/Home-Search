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
    service_names      = sort(concat(keys(aws_ecs_service.service), [aws_ecs_service.user_insight_worker.name]))
    one_shot_task_arns = { for name, task in aws_ecs_task_definition.one_shot : name => task.arn }
    service_task_arns = merge(
      { for name, task in aws_ecs_task_definition.service : name => task.arn },
      { "user-insight-worker" = aws_ecs_task_definition.user_insight_worker.arn },
    )
    image_digests = var.image_digests
  }
  description = "Non-secret deployment identities consumed by the release manifest."
}

output "backup_automation" {
  value = {
    bucket_name        = aws_s3_bucket.database_backup.id
    schedule_group     = aws_scheduler_schedule_group.database_backup.name
    schedule_names     = { for name, schedule in aws_scheduler_schedule.database_backup : name => schedule.name }
    retention_days     = 30
    included_databases = ["home_search", "home_search_admin", "home_search_user"]
    excluded_databases = ["home_search_coordinate_source"]
  }
}

output "streaming" {
  value = {
    cluster_arn                = aws_msk_serverless_cluster.events.arn
    bootstrap_brokers_sasl_iam = aws_msk_serverless_cluster.events.bootstrap_brokers_sasl_iam
    glue_registry_arn          = aws_glue_registry.events.arn
    operations_topic_arn       = aws_sns_topic.operations.arn
    property_relay_schedule    = aws_scheduler_schedule.property_event_relay.name
    scheduler_failure_dlq_arn  = aws_sqs_queue.scheduler_failure.arn
  }
  description = "Non-secret MSK IAM and Glue registry endpoints used by contract promotion and workloads."
}

output "market_news_automation" {
  value = {
    schedule_group = aws_scheduler_schedule_group.market_news.name
    schedule_names = { for name, schedule in aws_scheduler_schedule.market_news : name => schedule.name }
    public_enabled = var.enable_market_news_public
  }
  description = "Non-secret market news schedule identities and independent public-surface state."
}
