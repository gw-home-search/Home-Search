locals {
  alarm_actions = local.foundation_enabled ? [aws_sns_topic.alarm[0].arn] : []
}

resource "aws_sns_topic" "alarm" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-alarms"
  tags  = { Service = "observability" }
}

resource "aws_sns_topic_subscription" "alarm_email" {
  count     = local.foundation_enabled ? 1 : 0
  topic_arn = aws_sns_topic.alarm[0].arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

data "aws_iam_policy_document" "alarm_topic" {
  count = local.foundation_enabled ? 1 : 0
  statement {
    sid       = "AllowCloudWatchAlarms"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarm[0].arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alarm" {
  count  = local.foundation_enabled ? 1 : 0
  arn    = aws_sns_topic.alarm[0].arn
  policy = data.aws_iam_policy_document.alarm_topic[0].json
}

resource "aws_cloudwatch_log_group" "host" {
  count             = local.foundation_enabled ? 1 : 0
  name              = "/home-search/budget-production/host-nginx"
  retention_in_days = 14
  tags              = { Service = "edge", DataClass = "internal" }
}

resource "aws_cloudwatch_log_group" "recovery" {
  count             = local.foundation_enabled ? 1 : 0
  name              = "/home-search/budget-production/recovery"
  retention_in_days = 14
  tags              = { Service = "recovery", DataClass = "internal" }
}

resource "aws_cloudwatch_metric_alarm" "instance_system_status" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-system-status"
  alarm_description   = "Single host system status failed; request EC2 automatic recovery."
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  dimensions          = { InstanceId = aws_instance.host[0].id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = concat(local.alarm_actions, ["arn:aws:automate:${var.aws_region}:ec2:recover"])
}

resource "aws_cloudwatch_metric_alarm" "instance_status" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-instance-status"
  alarm_description   = "Single host instance status failed."
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  dimensions          = { InstanceId = aws_instance.host[0].id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cpu" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-cpu"
  alarm_description   = "Host CPU exceeds 80 percent."
  namespace           = "AWS/EC2"
  metric_name         = "CPUUtilization"
  dimensions          = { InstanceId = aws_instance.host[0].id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cpu_credit" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-cpu-credit"
  alarm_description   = "T3a CPU credit reserve is below one baseline hour."
  namespace           = "AWS/EC2"
  metric_name         = "CPUCreditBalance"
  dimensions          = { InstanceId = aws_instance.host[0].id }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "LessThanThreshold"
  threshold           = 72
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

locals {
  memory_thresholds = local.foundation_enabled ? { warning = 80, critical = 90 } : {}
}

resource "aws_cloudwatch_metric_alarm" "memory" {
  for_each            = local.memory_thresholds
  alarm_name          = "${local.name}-memory-${each.key}"
  alarm_description   = "Host memory used percent crossed ${each.value}."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "mem_used_percent"
  dimensions          = { InstanceId = aws_instance.host[0].id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = each.value
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "root_free" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-root-free"
  alarm_description   = "Root filesystem free space is below 8 GiB."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "disk_free"
  dimensions          = { InstanceId = aws_instance.host[0].id, path = "/", fstype = "xfs" }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "LessThanThreshold"
  threshold           = 8589934592
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "data_disk" {
  for_each = local.foundation_enabled ? {
    warning  = 17179869184
    critical = 8589934592
  } : {}
  alarm_name          = "${local.name}-data-disk-${each.key}"
  alarm_description   = "Data filesystem free bytes crossed the ${each.key} threshold."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "disk_free"
  dimensions          = { InstanceId = aws_instance.host[0].id, path = "/srv/home-search", fstype = "xfs" }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "LessThanThreshold"
  threshold           = each.value
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ecs_running" {
  count               = local.public_enabled ? 1 : 0
  alarm_name          = "${local.name}-always-on-services"
  alarm_description   = "One or more always-on ECS services has no running task."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "ECSMissingServiceCount"
  dimensions          = { ClusterName = aws_ecs_cluster.this[0].name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

locals {
  nginx_metric_filters = local.public_enabled ? {
    public-request = { pattern = "{ $.status = * }", metric = "PublicRequestCount", value = "1", unit = "Count" }
    public-5xx     = { pattern = "{ $.status >= 500 && $.status <= 599 }", metric = "Public5xxCount", value = "1", unit = "Count" }
    map-latency    = { pattern = "{ $.uri = \"/api/v1/map/*\" }", metric = "MapRequestTime", value = "$.request_time", unit = "Seconds" }
  } : {}
}

resource "aws_cloudwatch_log_metric_filter" "nginx" {
  for_each       = local.nginx_metric_filters
  name           = "${local.name}-${each.key}"
  pattern        = each.value.pattern
  log_group_name = aws_cloudwatch_log_group.host[0].name
  metric_transformation {
    name      = each.value.metric
    namespace = "HomeSearch/BudgetProduction"
    value     = each.value.value
    unit      = each.value.unit
  }
}

resource "aws_cloudwatch_metric_alarm" "map_p95" {
  count                                 = local.public_enabled ? 1 : 0
  alarm_name                            = "${local.name}-map-p95"
  alarm_description                     = "Map request p95 exceeded two seconds."
  namespace                             = "HomeSearch/BudgetProduction"
  metric_name                           = "MapRequestTime"
  extended_statistic                    = "p95"
  period                                = 300
  evaluation_periods                    = 2
  datapoints_to_alarm                   = 2
  comparison_operator                   = "GreaterThanThreshold"
  threshold                             = 2
  evaluate_low_sample_count_percentiles = "ignore"
  treat_missing_data                    = "notBreaching"
  alarm_actions                         = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "public_5xx" {
  count               = local.public_enabled ? 1 : 0
  alarm_name          = "${local.name}-public-5xx"
  alarm_description   = "Public 5xx rate exceeded one percent."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  metric_query {
    id          = "rate"
    expression  = "IF(requests > 0, 100 * errors / requests, 0)"
    label       = "Public 5xx percent"
    return_data = true
  }
  metric_query {
    id          = "errors"
    return_data = false
    metric {
      namespace   = "HomeSearch/BudgetProduction"
      metric_name = "Public5xxCount"
      period      = 60
      stat        = "Sum"
    }
  }
  metric_query {
    id          = "requests"
    return_data = false
    metric {
      namespace   = "HomeSearch/BudgetProduction"
      metric_name = "PublicRequestCount"
      period      = 60
      stat        = "Sum"
    }
  }
}

resource "aws_cloudwatch_log_metric_filter" "ai_temporary_failure" {
  count          = local.private_enabled ? 1 : 0
  name           = "${local.name}-ai-temporary-failure"
  pattern        = "\"TEMPORARY_FAILURE\""
  log_group_name = aws_cloudwatch_log_group.runtime["ai"].name
  metric_transformation {
    name      = "AiTemporaryFailureCount"
    namespace = "HomeSearch/BudgetProduction"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "ai_temporary_failure" {
  count               = local.private_enabled ? 1 : 0
  alarm_name          = "${local.name}-ai-temporary-failure"
  alarm_description   = "AI returned TEMPORARY_FAILURE."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "AiTemporaryFailureCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

locals {
  chatbot_terminal_filters = local.private_enabled ? {
    request = {
      pattern = "{ $.event = \"chatbot_terminal\" }"
      metric  = "ChatbotRequestCount"
      value   = "1"
    }
    partial = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.outcome = \"PARTIAL\" }"
      metric  = "ChatbotPartialCount"
      value   = "1"
    }
    answered = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.outcome = \"SUCCESS\" }"
      metric  = "ChatbotAnsweredCount"
      value   = "1"
    }
    safe_final = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.safeFinal = true }"
      metric  = "ChatbotSafeFinalCount"
      value   = "1"
    }
    timeout = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.outcome = \"UPSTREAM_TIMEOUT\" }"
      metric  = "ChatbotUpstreamTimeoutCount"
      value   = "1"
    }
    contract_rejected = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.outcome = \"CONTRACT_REJECTED\" }"
      metric  = "ChatbotContractRejectedCount"
      value   = "1"
    }
    missing_final = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.outcome = \"MISSING_FINAL\" }"
      metric  = "ChatbotMissingFinalCount"
      value   = "1"
    }
    latency = {
      pattern = "{ $.event = \"chatbot_terminal\" && $.latencyMs = * }"
      metric  = "ChatbotLatencyMs"
      value   = "$.latencyMs"
    }
  } : {}
}

resource "aws_cloudwatch_log_metric_filter" "chatbot_terminal" {
  for_each       = local.chatbot_terminal_filters
  name           = "${local.name}-chatbot-${replace(each.key, "_", "-")}"
  pattern        = each.value.pattern
  log_group_name = aws_cloudwatch_log_group.runtime["chat-bff"].name
  metric_transformation {
    name      = each.value.metric
    namespace = "HomeSearch/BudgetProduction"
    value     = each.value.value
    unit      = each.key == "latency" ? "Milliseconds" : "Count"
  }
}

locals {
  chatbot_intent_latency_filters = local.private_enabled ? {
    direct_property = {
      intent = "DIRECT_PROPERTY"
      metric = "ChatbotDirectPropertyLatencyMs"
    }
    complex_overview = {
      intent = "COMPLEX_OVERVIEW"
      metric = "ChatbotComplexOverviewLatencyMs"
    }
    reference_compound = {
      intent = "REFERENCE_COMPOUND"
      metric = "ChatbotReferenceCompoundLatencyMs"
    }
    trend = {
      intent = "TREND"
      metric = "ChatbotTrendLatencyMs"
    }
    comparison = {
      intent = "COMPARISON"
      metric = "ChatbotComparisonLatencyMs"
    }
    recommendation = {
      intent = "RECOMMENDATION"
      metric = "ChatbotRecommendationLatencyMs"
    }
  } : {}
  chatbot_intent_latency_alarms = {
    direct_property = {
      metric    = "ChatbotDirectPropertyLatencyMs"
      threshold = 10000
    }
    complex_overview = {
      metric    = "ChatbotComplexOverviewLatencyMs"
      threshold = 10000
    }
    reference_compound = {
      metric    = "ChatbotReferenceCompoundLatencyMs"
      threshold = 15000
    }
    trend = {
      metric    = "ChatbotTrendLatencyMs"
      threshold = 10000
    }
    comparison = {
      metric    = "ChatbotComparisonLatencyMs"
      threshold = 15000
    }
    recommendation = {
      metric    = "ChatbotRecommendationLatencyMs"
      threshold = 20000
    }
  }
}

resource "aws_cloudwatch_log_metric_filter" "chatbot_intent_latency" {
  for_each       = local.chatbot_intent_latency_filters
  name           = "${local.name}-chatbot-${replace(each.key, "_", "-")}-latency"
  pattern        = "{ $.event = \"chatbot_terminal\" && $.intent = \"${each.value.intent}\" && $.latencyMs = * }"
  log_group_name = aws_cloudwatch_log_group.runtime["chat-bff"].name
  metric_transformation {
    name      = each.value.metric
    namespace = "HomeSearch/BudgetProduction"
    value     = "$.latencyMs"
    unit      = "Milliseconds"
  }
}

resource "aws_cloudwatch_log_metric_filter" "ai_capability_unavailable" {
  count          = local.private_enabled ? 1 : 0
  name           = "${local.name}-ai-capability-unavailable"
  pattern        = "{ $.event = \"chatbot_capability_terminal\" && $.outcome = %^(unavailable|timeout|failed)$% }"
  log_group_name = aws_cloudwatch_log_group.runtime["ai"].name
  metric_transformation {
    name       = "UnavailableCount"
    namespace  = "HomeSearch/BudgetProduction"
    value      = "1"
    unit       = "Count"
    dimensions = { Capability = "$.capability" }
  }
}

resource "aws_cloudwatch_metric_alarm" "chatbot_safe_final_warning" {
  count               = local.private_enabled ? 1 : 0
  alarm_name          = "${local.name}-chatbot-safe-final-warning"
  alarm_description   = "Chatbot emitted at least one safe final in five minutes."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "ChatbotSafeFinalCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "chatbot_safe_final_ratio_critical" {
  count               = local.private_enabled ? 1 : 0
  alarm_name          = "${local.name}-chatbot-safe-final-ratio-critical"
  alarm_description   = "Chatbot safe-final ratio reached five percent with at least five requests."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 5
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  metric_query {
    id          = "ratio"
    expression  = "IF(requests >= 5, 100 * safe / requests, 0)"
    label       = "Chatbot safe-final percent"
    return_data = true
  }
  metric_query {
    id          = "safe"
    return_data = false
    metric {
      namespace   = "HomeSearch/BudgetProduction"
      metric_name = "ChatbotSafeFinalCount"
      period      = 300
      stat        = "Sum"
    }
  }
  metric_query {
    id          = "requests"
    return_data = false
    metric {
      namespace   = "HomeSearch/BudgetProduction"
      metric_name = "ChatbotRequestCount"
      period      = 300
      stat        = "Sum"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "chatbot_contract_critical" {
  count               = local.private_enabled ? 1 : 0
  alarm_name          = "${local.name}-chatbot-contract-critical"
  alarm_description   = "Chatbot contract was rejected or SSE final was missing."
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  metric_query {
    id          = "critical"
    expression  = "rejected + missing"
    label       = "Chatbot contract critical count"
    return_data = true
  }
  metric_query {
    id          = "rejected"
    return_data = false
    metric {
      namespace   = "HomeSearch/BudgetProduction"
      metric_name = "ChatbotContractRejectedCount"
      period      = 300
      stat        = "Sum"
    }
  }
  metric_query {
    id          = "missing"
    return_data = false
    metric {
      namespace   = "HomeSearch/BudgetProduction"
      metric_name = "ChatbotMissingFinalCount"
      period      = 300
      stat        = "Sum"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "chatbot_latency_warning" {
  count               = local.private_enabled ? 1 : 0
  alarm_name          = "${local.name}-chatbot-p95-latency-warning"
  alarm_description   = "Chatbot p95 latency exceeded thirty seconds."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "ChatbotLatencyMs"
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 30000
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "chatbot_intent_latency_warning" {
  for_each            = local.private_enabled ? local.chatbot_intent_latency_alarms : {}
  alarm_name          = "${local.name}-chatbot-${replace(each.key, "_", "-")}-p95-latency-warning"
  alarm_description   = "Chatbot bounded intent p95 latency exceeded its release threshold."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = each.value.metric
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "chatbot_upstream_timeout_critical" {
  count               = local.private_enabled ? 1 : 0
  alarm_name          = "${local.name}-chatbot-upstream-timeout-critical"
  alarm_description   = "Chatbot upstream timeout occurred at least once."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "ChatbotUpstreamTimeoutCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

locals {
  backup_log_filters = local.data_enabled ? {
    failure = { pattern = "{ $.metric = \"backup_run_failure\" }", metric = "OperationalFailureCount" }
  } : {}
  recovery_log_filters = local.foundation_enabled ? {
    failure  = { pattern = "\"ERROR:\"", metric = "OperationalFailureCount" }
    checksum = { pattern = "\"checksum mismatch\"", metric = "OperationalFailureCount" }
  } : {}
}

resource "aws_cloudwatch_log_metric_filter" "backup" {
  for_each       = local.backup_log_filters
  name           = "${local.name}-backup-${each.key}"
  pattern        = each.value.pattern
  log_group_name = aws_cloudwatch_log_group.runtime["scheduled-backup"].name
  metric_transformation {
    name      = each.value.metric
    namespace = "HomeSearch/BudgetProduction"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_log_metric_filter" "recovery" {
  for_each       = local.recovery_log_filters
  name           = "${local.name}-recovery-${each.key}"
  pattern        = each.value.pattern
  log_group_name = aws_cloudwatch_log_group.recovery[0].name
  metric_transformation {
    name      = each.value.metric
    namespace = "HomeSearch/BudgetProduction"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "backup_age" {
  count               = var.backup_schedules_enabled ? 1 : 0
  alarm_name          = "${local.name}-backup-age"
  alarm_description   = "Latest logical backup is older than 26 hours."
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "BackupAgeSeconds"
  statistic           = "Maximum"
  period              = 3600
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 93600
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "operational_failure" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-backup-restore-failure"
  namespace           = "HomeSearch/BudgetProduction"
  metric_name         = "OperationalFailureCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "certificate_expiry" {
  count               = local.foundation_enabled ? 1 : 0
  alarm_name          = "${local.name}-certificate-expiry"
  alarm_description   = "Exportable ACM certificate expires within 30 days."
  namespace           = "AWS/CertificateManager"
  metric_name         = "DaysToExpiry"
  dimensions          = { CertificateArn = aws_acm_certificate.public[0].arn }
  statistic           = "Minimum"
  period              = 86400
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 30
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
}
