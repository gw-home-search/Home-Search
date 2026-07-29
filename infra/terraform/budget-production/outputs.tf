output "deployment_phase" {
  value       = var.deployment_phase
  description = "Last Terraform rollout phase applied to this state."
}

output "platform_repository_urls" {
  value       = { for name, repository in aws_ecr_repository.platform : name => repository.repository_url }
  description = "Budget-only immutable platform repositories."
}

output "host_instance_id" {
  value       = try(aws_instance.host[0].id, null)
  description = "Single protected budget-production EC2 instance ID."
}

output "ami_id" {
  value       = local.foundation_enabled ? var.ami_id : null
  description = "Exact ECS-optimized AMI pinned by the first foundation apply."
}

output "availability_zone" {
  value       = local.foundation_enabled ? var.availability_zone : null
  description = "Stable single availability zone pinned by the first foundation apply."
}

output "data_volume_id" {
  value       = try(aws_ebs_volume.data[0].id, null)
  description = "Protected data EBS volume ID."
}

output "elastic_ip" {
  value       = try(aws_eip.public[0].public_ip, null)
  description = "Stable public address used for pre-DNS curl --resolve verification."
}

output "ecs_cluster_name" {
  value       = try(aws_ecs_cluster.this[0].name, null)
  description = "Single-node ECS cluster name."
}

output "backup_bucket_name" {
  value       = try(aws_s3_bucket.backup[0].id, null)
  description = "Object-locked logical backup bucket."
}

output "reference_raw_bucket_name" {
  value       = try(aws_s3_bucket.reference_raw[0].id, null)
  description = "Versioned reference raw bucket."
}

output "certificate_arn" {
  value       = try(aws_acm_certificate.public[0].arn, null)
  description = "Exportable single-FQDN ACM certificate ARN."
}

output "recovery_security_group_id" {
  value       = try(aws_security_group.recovery[0].id, null)
  description = "Ingress-free security group for ephemeral restore rehearsals."
}

output "recovery_instance_profile_name" {
  value       = try(aws_iam_instance_profile.recovery[0].name, null)
  description = "Least-privilege instance profile for ephemeral restore rehearsals."
}

output "public_subnet_id" {
  value       = try(aws_subnet.public[0].id, null)
  description = "Pinned single-AZ public subnet used by the ingress-free recovery runner."
}

output "one_shot_task_definition_arns" {
  value       = { for name, task in aws_ecs_task_definition.one_shot : name => task.arn }
  description = "Reviewed EC2 bridge one-shot task definitions keyed by operation."
}

output "application_service_names" {
  value       = sort(keys(aws_ecs_service.application))
  description = "Application ECS services present in the selected phase."
}

output "platform_service_names" {
  value       = sort(keys(aws_ecs_service.platform))
  description = "Platform ECS services present in the selected phase."
}

output "data_services_enabled" {
  value       = var.data_services_enabled
  description = "Explicit PostgreSQL and Valkey activation gate."
}

output "public_dns_enabled" {
  value       = var.public_dns_enabled
  description = "Explicit final Route53 cutover gate."
}

output "backup_schedules_enabled" {
  value       = var.backup_schedules_enabled
  description = "Post-cutover DLM and logical backup schedule gate."
}
