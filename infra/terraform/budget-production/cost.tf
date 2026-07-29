resource "aws_budgets_budget" "monthly" {
  count        = local.foundation_enabled ? 1 : 0
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "ABSOLUTE_VALUE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "ABSOLUTE_VALUE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alarm_email]
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "ABSOLUTE_VALUE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alarm_email]
  }

  tags = { Service = "cost-control" }
}

resource "aws_ce_anomaly_subscription" "daily" {
  count            = local.foundation_enabled ? 1 : 0
  name             = "${local.name}-daily"
  frequency        = "DAILY"
  monitor_arn_list = [var.cost_anomaly_monitor_arn]

  subscriber {
    address = var.alarm_email
    type    = "EMAIL"
  }

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = ["10"]
    }
  }

  tags = { Service = "cost-control" }

  lifecycle {
    precondition {
      condition     = var.cost_anomaly_monitor_arn != ""
      error_message = "Foundation requires one exact existing account-wide SERVICE anomaly monitor ARN."
    }
  }
}
