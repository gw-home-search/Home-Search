locals {
  alarm_actions           = [var.alarm_topic_arn]
  amp_alert_receiver_name = "production-sns"
  dashboard_sections      = ["SLO overview", "ECS and data capacity"]
  amp_alert_rules = {
    groups = [{
      name = "home-search-production"
      rules = [
        {
          alert       = "MapP95Exceeded"
          expr        = "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service=\"property-api\",uri=~\"/api/v1/map/(complexes|regions)\"}[5m]))) > 2"
          for         = "10m"
          labels      = { severity = "critical", service = "property-api" }
          annotations = { summary = "Map endpoint p95 exceeded the two-second release gate." }
        },
        {
          alert       = "MapErrorRateExceeded"
          expr        = "sum(rate(http_server_requests_seconds_count{service=\"property-api\",uri=~\"/api/v1/map/(complexes|regions)\",status=~\"5..\"}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{service=\"property-api\",uri=~\"/api/v1/map/(complexes|regions)\"}[5m])), 1e-9) > 0.01"
          for         = "5m"
          labels      = { severity = "critical", service = "property-api" }
          annotations = { summary = "Map endpoint error rate exceeded one percent." }
        },
        {
          alert       = "AiTemporaryFailure"
          expr        = "increase(home_ai_supervisor_graph_total{service=\"ai\",terminal_reason=\"TEMPORARY_FAILURE\"}[5m]) > 0"
          for         = "0m"
          labels      = { severity = "high", service = "ai" }
          annotations = { summary = "AI Supervisor Graph returned temporary failures." }
        },
        {
          alert       = "AiMissingFinal"
          expr        = "increase(home_ai_supervisor_graph_total{service=\"ai\",outcome=\"safe_final\",terminal_reason=\"invariant_or_runtime\"}[5m]) > 0"
          for         = "0m"
          labels      = { severity = "critical", service = "ai" }
          annotations = { summary = "AI required a safe final because its terminal contract was not satisfied." }
        },
        {
          alert       = "MetricsScrapeMissing"
          expr        = "up{environment=\"production\"} == 0"
          for         = "5m"
          labels      = { severity = "high" }
          annotations = { summary = "A production metrics scrape target is unavailable." }
        },
      ]
    }]
  }
}

resource "aws_iam_role" "amp_alertmanager" {
  name = "${local.name}-amp-alertmanager"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "aps.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
        ArnEquals    = { "aws:SourceArn" = aws_prometheus_workspace.this.arn }
      }
    }]
  })
}

resource "aws_iam_role_policy" "amp_alertmanager" {
  name = "publish-approved-alarm-topic"
  role = aws_iam_role.amp_alertmanager.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sns:Publish"]
      Resource = [var.alarm_topic_arn]
    }]
  })
}

resource "aws_prometheus_rule_group_namespace" "production" {
  name         = "home-search-production"
  workspace_id = aws_prometheus_workspace.this.id
  data         = yamlencode(local.amp_alert_rules)
}

resource "aws_prometheus_alert_manager_definition" "production" {
  workspace_id = aws_prometheus_workspace.this.id
  definition = yamlencode({
    alertmanager_config = {
      route = {
        receiver        = local.amp_alert_receiver_name
        group_by        = ["alertname", "service"]
        group_wait      = "30s"
        group_interval  = "5m"
        repeat_interval = "4h"
      }
      receivers = [{
        name = local.amp_alert_receiver_name
        sns_configs = [{
          topic_arn = var.alarm_topic_arn
          sigv4 = {
            region   = var.aws_region
            role_arn = aws_iam_role.amp_alertmanager.arn
          }
        }]
      }]
    }
  })
}

resource "aws_cloudwatch_metric_alarm" "ecs_running_task" {
  for_each            = local.service_specs
  alarm_name          = "${local.name}-${each.key}-running-task"
  alarm_description   = "Production ECS running task count is below the approved desired count."
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  dimensions          = { ClusterName = aws_ecs_cluster.this.name, ServiceName = aws_ecs_service.service[each.key].name }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "LessThanThreshold"
  threshold           = var.enable_services ? var.core_desired_count : 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  for_each            = aws_db_instance.service
  alarm_name          = "${local.name}-${each.key}-rds-cpu"
  alarm_description   = "RDS CPU exceeds the production capacity gate."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = each.value.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 60
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  for_each            = aws_db_instance.service
  alarm_name          = "${local.name}-${each.key}-rds-free-storage"
  alarm_description   = "RDS free storage is below 20 GiB."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = each.value.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 21474836480
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "public_unhealthy_targets" {
  alarm_name          = "${local.name}-public-unhealthy-targets"
  alarm_description   = "The public gateway target group has unhealthy tasks."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = aws_lb.public.arn_suffix, TargetGroup = aws_lb_target_group.gateway["public-gateway"].arn_suffix }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = var.enable_services ? "breaching" : "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "public_p95_latency" {
  alarm_name                            = "${local.name}-public-p95-latency"
  alarm_description                     = "Public ALB p95 latency exceeded two seconds. AMP provides route-level map enforcement."
  namespace                             = "AWS/ApplicationELB"
  metric_name                           = "TargetResponseTime"
  dimensions                            = { LoadBalancer = aws_lb.public.arn_suffix, TargetGroup = aws_lb_target_group.gateway["public-gateway"].arn_suffix }
  extended_statistic                    = "p95"
  period                                = 60
  evaluation_periods                    = 5
  datapoints_to_alarm                   = 3
  comparison_operator                   = "GreaterThanThreshold"
  threshold                             = 2
  evaluate_low_sample_count_percentiles = "ignore"
  treat_missing_data                    = "notBreaching"
  alarm_actions                         = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "public_5xx_rate" {
  alarm_name          = "${local.name}-public-5xx-rate"
  alarm_description   = "Public target 5xx responses exceeded one percent."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions

  metric_query {
    id          = "error_rate"
    expression  = "IF(requests > 0, 100 * errors / requests, 0)"
    label       = "Public target 5xx percent"
    return_data = true
  }
  metric_query {
    id          = "errors"
    return_data = false
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = { LoadBalancer = aws_lb.public.arn_suffix, TargetGroup = aws_lb_target_group.gateway["public-gateway"].arn_suffix }
      period      = 60
      stat        = "Sum"
    }
  }
  metric_query {
    id          = "requests"
    return_data = false
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      dimensions  = { LoadBalancer = aws_lb.public.arn_suffix, TargetGroup = aws_lb_target_group.gateway["public-gateway"].arn_suffix }
      period      = 60
      stat        = "Sum"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "valkey_evictions" {
  alarm_name          = "${local.name}-valkey-evictions"
  alarm_description   = "Valkey evicted keys, indicating memory pressure."
  namespace           = "AWS/ElastiCache"
  metric_name         = "Evictions"
  dimensions          = { ReplicationGroupId = aws_elasticache_replication_group.this.replication_group_id }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "valkey_replication_lag" {
  alarm_name          = "${local.name}-valkey-replication-lag"
  alarm_description   = "Valkey replica lag threatens automatic failover readiness."
  namespace           = "AWS/ElastiCache"
  metric_name         = "ReplicationLag"
  dimensions          = { ReplicationGroupId = aws_elasticache_replication_group.this.replication_group_id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 30
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "certificate_expiry" {
  alarm_name          = "${local.name}-public-certificate-expiry"
  alarm_description   = "Public ACM certificate expires within 30 days."
  namespace           = "AWS/CertificateManager"
  metric_name         = "DaysToExpiry"
  dimensions          = { CertificateArn = var.public_certificate_arn }
  statistic           = "Minimum"
  period              = 86400
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 30
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_dashboard" "production" {
  dashboard_name = "${var.project_name}-production"
  dashboard_body = jsonencode({
    widgets = [
      {
        type       = "text", x = 0, y = 0, width = 24, height = 1
        properties = { markdown = "# ${local.dashboard_sections[0]}\nPublic ALB availability, latency, and target health. Route-level map and AI terminal gates are evaluated in AMP." }
      },
      {
        type = "metric", x = 0, y = 1, width = 12, height = 6
        properties = {
          title  = "Public latency and 5xx"
          region = var.aws_region
          view   = "timeSeries"
          period = 60
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.public.arn_suffix, { stat = "p95" }],
            [".", "HTTPCode_Target_5XX_Count", ".", ".", { stat = "Sum", yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 1, width = 12, height = 6
        properties = {
          title   = "Public target health"
          region  = var.aws_region
          view    = "timeSeries"
          period  = 60
          metrics = [["AWS/ApplicationELB", "UnHealthyHostCount", "TargetGroup", aws_lb_target_group.gateway["public-gateway"].arn_suffix, "LoadBalancer", aws_lb.public.arn_suffix, { stat = "Maximum" }]]
        }
      },
      {
        type       = "text", x = 0, y = 7, width = 24, height = 1
        properties = { markdown = "# ${local.dashboard_sections[1]}\nRunning tasks, RDS CPU/storage, and Valkey pressure." }
      },
      {
        type = "metric", x = 0, y = 8, width = 12, height = 8
        properties = {
          title   = "ECS running tasks"
          region  = var.aws_region
          view    = "timeSeries"
          period  = 60
          metrics = [for name in keys(local.service_specs) : ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", aws_ecs_cluster.this.name, "ServiceName", name]]
        }
      },
      {
        type = "metric", x = 12, y = 8, width = 12, height = 8
        properties = {
          title   = "RDS CPU"
          region  = var.aws_region
          view    = "timeSeries"
          period  = 300
          metrics = [for name, database in aws_db_instance.service : ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", database.identifier, { label = name }]]
        }
      },
      {
        type = "metric", x = 0, y = 16, width = 12, height = 6
        properties = {
          title  = "Valkey eviction and lag"
          region = var.aws_region
          view   = "timeSeries"
          period = 300
          metrics = [
            ["AWS/ElastiCache", "Evictions", "ReplicationGroupId", aws_elasticache_replication_group.this.replication_group_id, { stat = "Sum" }],
            [".", "ReplicationLag", ".", ".", { stat = "Maximum", yAxis = "right" }],
          ]
        }
      },
    ]
  })
}
