locals {
  cost_anomaly_threshold_usd = tostring(max(10, ceil(var.monthly_budget_usd * 0.01)))
}

resource "aws_ce_anomaly_monitor" "services" {
  name              = "${local.name}-services"
  monitor_type      = "DIMENSIONAL"
  monitor_dimension = "SERVICE"
}

resource "aws_ce_anomaly_subscription" "daily" {
  name             = "${local.name}-daily"
  frequency        = "DAILY"
  monitor_arn_list = [aws_ce_anomaly_monitor.services.arn]

  dynamic "subscriber" {
    for_each = var.budget_notification_emails
    content {
      address = subscriber.value
      type    = "EMAIL"
    }
  }

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = [local.cost_anomaly_threshold_usd]
    }
  }
}
