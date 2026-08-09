locals {
  rtms_failure_namespace = "HomeSearch/BudgetProduction"
  rtms_cluster_arn       = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:cluster/${local.name}"
  rtms_ml_service_arn    = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:service/${local.name}/ml"
  rtms_refresh_definition = local.data_enabled ? {
    Comment        = "04:30 KST RTMS refresh with backup, capacity, and ML restoration guards"
    StartAt        = "CHECK_BACKUP"
    TimeoutSeconds = 18000
    States = {
      CHECK_BACKUP = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:ecs:listTasks"
        Parameters = {
          Cluster       = local.rtms_cluster_arn
          Family        = "${local.name}-scheduled-backup"
          DesiredStatus = "RUNNING"
        }
        ResultPath = "$.backupRunning"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE" }]
        Next       = "BACKUP_RUNNING"
      }
      BACKUP_RUNNING = {
        Type = "Choice"
        Choices = [{
          Variable  = "$.backupRunning.TaskArns[0]"
          IsPresent = true
          Next      = "BACKUP_WAIT_LIMIT"
        }]
        Default = "CAPTURE_ML"
      }
      BACKUP_WAIT_LIMIT = {
        Type = "Choice"
        Choices = [{
          Variable                 = "$.wait.attempt"
          NumericGreaterThanEquals = 12
          Next                     = "SET_BACKUP_TIMEOUT_FAILURE"
        }]
        Default = "WAIT_BACKUP"
      }
      WAIT_BACKUP = { Type = "Wait", Seconds = 300, Next = "INCREMENT_BACKUP_WAIT" }
      INCREMENT_BACKUP_WAIT = {
        Type       = "Pass"
        Parameters = { "attempt.$" = "States.MathAdd($.wait.attempt, 1)" }
        ResultPath = "$.wait"
        Next       = "CHECK_BACKUP"
      }
      CAPTURE_ML = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:describeServices"
        Parameters = { Cluster = local.rtms_cluster_arn, Services = ["ml"] }
        ResultPath = "$.mlService"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE" }]
        Next       = "CAPTURE_ML_STATE"
      }
      CAPTURE_ML_STATE = {
        Type = "Pass"
        Parameters = {
          "taskDefinition.$" = "$.mlService.Services[0].TaskDefinition"
          "desiredCount.$"   = "$.mlService.Services[0].DesiredCount"
        }
        ResultPath = "$.mlOriginal"
        Next       = "CAPTURE_ACTIVE_TASKS"
      }
      CAPTURE_ACTIVE_TASKS = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:listTasks"
        Parameters = { Cluster = local.rtms_cluster_arn, DesiredStatus = "RUNNING" }
        ResultPath = "$.activeTasks"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE" }]
        Next       = "ACTIVE_TASKS_PRESENT"
      }
      ACTIVE_TASKS_PRESENT = {
        Type = "Choice"
        Choices = [{
          Variable  = "$.activeTasks.TaskArns[0]"
          IsPresent = true
          Next      = "DESCRIBE_ACTIVE_TASKS"
        }]
        Default = "CHECK_CAPACITY"
      }
      DESCRIBE_ACTIVE_TASKS = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:describeTasks"
        Parameters = { Cluster = local.rtms_cluster_arn, "Tasks.$" = "$.activeTasks.TaskArns" }
        ResultPath = "$.activeTaskDetails"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE" }]
        Next       = "CHECK_CAPACITY"
      }
      CHECK_CAPACITY = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:listContainerInstances"
        Parameters = { Cluster = local.rtms_cluster_arn, Status = "ACTIVE" }
        ResultPath = "$.containerInstanceList"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE" }]
        Next       = "CONTAINER_INSTANCE_PRESENT"
      }
      CONTAINER_INSTANCE_PRESENT = {
        Type = "Choice"
        Choices = [{
          Variable  = "$.containerInstanceList.ContainerInstanceArns[0]"
          IsPresent = true
          Next      = "DESCRIBE_CAPACITY"
        }]
        Default = "SET_CAPACITY_FAILURE"
      }
      DESCRIBE_CAPACITY = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:ecs:describeContainerInstances"
        Parameters = {
          Cluster                = local.rtms_cluster_arn
          "ContainerInstances.$" = "States.Array($.containerInstanceList.ContainerInstanceArns[0])"
        }
        ResultPath = "$.capacity"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE" }]
        Next       = "CAPACITY_SUFFICIENT"
      }
      CAPACITY_SUFFICIENT = {
        Type = "Choice"
        Choices = [{
          And = [
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[0].Name", StringEquals = "CPU" },
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[0].IntegerValue", NumericGreaterThanEquals = 512 },
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[1].Name", StringEquals = "MEMORY" },
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[1].IntegerValue", NumericGreaterThanEquals = 1024 },
          ]
          Next = "RUN_RTMS"
        }]
        Default = "ML_CAN_STOP"
      }
      ML_CAN_STOP = {
        Type = "Choice"
        Choices = [{
          Variable           = "$.mlOriginal.desiredCount"
          NumericGreaterThan = 0
          Next               = "MARK_ML_STOP_REQUESTED"
        }]
        Default = "SET_CAPACITY_FAILURE"
      }
      MARK_ML_STOP_REQUESTED = { Type = "Pass", Result = true, ResultPath = "$.mlStopped", Next = "OPTIONAL_STOP_ML" }
      OPTIONAL_STOP_ML = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:updateService"
        Parameters = { Cluster = local.rtms_cluster_arn, Service = "ml", DesiredCount = 0 }
        ResultPath = null
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE_RESTORE" }]
        Next       = "WAIT_ML_STOP"
      }
      WAIT_ML_STOP = { Type = "Wait", Seconds = 15, Next = "DESCRIBE_ML_STOP" }
      DESCRIBE_ML_STOP = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:describeServices"
        Parameters = { Cluster = local.rtms_cluster_arn, Services = ["ml"] }
        ResultPath = "$.mlStoppedService"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE_RESTORE" }]
        Next       = "ML_STOPPED"
      }
      ML_STOPPED = {
        Type = "Choice"
        Choices = [{
          And = [
            { Variable = "$.mlStoppedService.Services[0].RunningCount", NumericEquals = 0 },
            { Variable = "$.mlStoppedService.Services[0].PendingCount", NumericEquals = 0 },
          ]
          Next = "RECHECK_CAPACITY"
        }]
        Default = "ML_STOP_WAIT_LIMIT"
      }
      ML_STOP_WAIT_LIMIT = {
        Type    = "Choice"
        Choices = [{ Variable = "$.mlStopWait.attempt", NumericGreaterThanEquals = 40, Next = "SET_CAPACITY_FAILURE_RESTORE" }]
        Default = "INCREMENT_ML_STOP_WAIT"
      }
      INCREMENT_ML_STOP_WAIT = {
        Type       = "Pass"
        Parameters = { "attempt.$" = "States.MathAdd($.mlStopWait.attempt, 1)" }
        ResultPath = "$.mlStopWait"
        Next       = "WAIT_ML_STOP"
      }
      RECHECK_CAPACITY = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:listContainerInstances"
        Parameters = { Cluster = local.rtms_cluster_arn, Status = "ACTIVE" }
        ResultPath = "$.containerInstanceList"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE_RESTORE" }]
        Next       = "CONTAINER_INSTANCE_PRESENT_AFTER_ML_STOP"
      }
      CONTAINER_INSTANCE_PRESENT_AFTER_ML_STOP = {
        Type    = "Choice"
        Choices = [{ Variable = "$.containerInstanceList.ContainerInstanceArns[0]", IsPresent = true, Next = "DESCRIBE_CAPACITY_AFTER_ML_STOP" }]
        Default = "SET_CAPACITY_FAILURE_RESTORE"
      }
      DESCRIBE_CAPACITY_AFTER_ML_STOP = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:ecs:describeContainerInstances"
        Parameters = {
          Cluster                = local.rtms_cluster_arn
          "ContainerInstances.$" = "States.Array($.containerInstanceList.ContainerInstanceArns[0])"
        }
        ResultPath = "$.capacity"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.orchestrationError", Next = "SET_CAPACITY_FAILURE_RESTORE" }]
        Next       = "CAPACITY_SUFFICIENT_AFTER_ML_STOP"
      }
      CAPACITY_SUFFICIENT_AFTER_ML_STOP = {
        Type = "Choice"
        Choices = [{
          And = [
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[0].Name", StringEquals = "CPU" },
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[0].IntegerValue", NumericGreaterThanEquals = 512 },
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[1].Name", StringEquals = "MEMORY" },
            { Variable = "$.capacity.ContainerInstances[0].RemainingResources[1].IntegerValue", NumericGreaterThanEquals = 1024 },
          ]
          Next = "RUN_RTMS"
        }]
        Default = "SET_CAPACITY_FAILURE_RESTORE"
      }
      RUN_RTMS = {
        Type           = "Task"
        Resource       = "arn:aws:states:::ecs:runTask.sync"
        TimeoutSeconds = 10800
        Parameters = {
          Cluster         = local.rtms_cluster_arn
          TaskDefinition  = var.rtms_refresh_task_definition_arn
          LaunchType      = "EC2"
          "ClientToken.$" = "$.schedulerExecutionId"
          Overrides = { ContainerOverrides = [{
            Name        = "rtms-daily-refresh"
            "Command.$" = "States.Array(States.Format('schedulerExecutionId={}', $.schedulerExecutionId))"
          }] }
        }
        ResultPath = "$.rtmsResult"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.rtmsError", Next = "SET_RTMS_FAILURE" }]
        Next       = "RTMS_EXIT_SUCCESS"
      }
      RTMS_EXIT_SUCCESS = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.rtmsResult.Tasks[0].Containers[0].ExitCode"
          NumericEquals = 0
          Next          = "SET_SUCCESS"
        }]
        Default = "SET_RTMS_FAILURE"
      }
      SET_SUCCESS                  = { Type = "Pass", Result = "success", ResultPath = "$.outcome", Next = "RESTORE_IF_STOPPED" }
      SET_RTMS_FAILURE             = { Type = "Pass", Result = "rtmsFailure", ResultPath = "$.outcome", Next = "RESTORE_IF_STOPPED" }
      SET_BACKUP_TIMEOUT_FAILURE   = { Type = "Pass", Result = "backupTimeout", ResultPath = "$.outcome", Next = "EMIT_RTMS_FAILURE" }
      SET_CAPACITY_FAILURE         = { Type = "Pass", Result = "capacityFailure", ResultPath = "$.outcome", Next = "EMIT_RTMS_FAILURE" }
      SET_CAPACITY_FAILURE_RESTORE = { Type = "Pass", Result = "capacityFailure", ResultPath = "$.outcome", Next = "RESTORE_ML" }
      RESTORE_IF_STOPPED = {
        Type    = "Choice"
        Choices = [{ Variable = "$.mlStopped", BooleanEquals = true, Next = "RESTORE_ML" }]
        Default = "OUTCOME"
      }
      RESTORE_ML = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:ecs:updateService"
        Parameters = {
          Cluster            = local.rtms_cluster_arn
          Service            = "ml"
          "TaskDefinition.$" = "$.mlOriginal.taskDefinition"
          "DesiredCount.$"   = "$.mlOriginal.desiredCount"
        }
        ResultPath = null
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.mlRestoreError", Next = "EMIT_ML_RECOVERY_CRITICAL" }]
        Next       = "WAIT_ML_RESTORE"
      }
      WAIT_ML_RESTORE = { Type = "Wait", Seconds = 15, Next = "VERIFY_ML" }
      VERIFY_ML = {
        Type       = "Task"
        Resource   = "arn:aws:states:::aws-sdk:ecs:describeServices"
        Parameters = { Cluster = local.rtms_cluster_arn, Services = ["ml"] }
        ResultPath = "$.mlRestoredService"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.mlRestoreError", Next = "EMIT_ML_RECOVERY_CRITICAL" }]
        Next       = "ML_RESTORED"
      }
      ML_RESTORED = {
        Type = "Choice"
        Choices = [{
          And = [
            { Variable = "$.mlRestoredService.Services[0].TaskDefinition", StringEqualsPath = "$.mlOriginal.taskDefinition" },
            { Variable = "$.mlRestoredService.Services[0].DesiredCount", NumericEqualsPath = "$.mlOriginal.desiredCount" },
            { Variable = "$.mlRestoredService.Services[0].RunningCount", NumericEqualsPath = "$.mlOriginal.desiredCount" },
            { Variable = "$.mlRestoredService.Services[0].PendingCount", NumericEquals = 0 },
            { Variable = "$.mlRestoredService.Services[0].Deployments[0].Status", StringEquals = "PRIMARY" },
            { Variable = "$.mlRestoredService.Services[0].Deployments[0].RolloutState", StringEquals = "COMPLETED" },
          ]
          Next = "OUTCOME"
        }]
        Default = "ML_RESTORE_WAIT_LIMIT"
      }
      ML_RESTORE_WAIT_LIMIT = {
        Type    = "Choice"
        Choices = [{ Variable = "$.mlRestoreWait.attempt", NumericGreaterThanEquals = 40, Next = "EMIT_ML_RECOVERY_CRITICAL" }]
        Default = "INCREMENT_ML_RESTORE_WAIT"
      }
      INCREMENT_ML_RESTORE_WAIT = {
        Type       = "Pass"
        Parameters = { "attempt.$" = "States.MathAdd($.mlRestoreWait.attempt, 1)" }
        ResultPath = "$.mlRestoreWait"
        Next       = "WAIT_ML_RESTORE"
      }
      OUTCOME = {
        Type    = "Choice"
        Choices = [{ Variable = "$.outcome", StringEquals = "success", Next = "COMPLETE" }]
        Default = "EMIT_RTMS_FAILURE"
      }
      EMIT_RTMS_FAILURE = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:cloudwatch:putMetricData"
        Parameters = {
          Namespace  = local.rtms_failure_namespace
          MetricData = [{ MetricName = "RtmsRefreshFailure", Unit = "Count", Value = 1 }]
        }
        ResultPath = null
        Next       = "RTMS_FAILED"
      }
      EMIT_ML_RECOVERY_CRITICAL = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:cloudwatch:putMetricData"
        Parameters = {
          Namespace  = local.rtms_failure_namespace
          MetricData = [{ MetricName = "MlRecoveryCritical", Unit = "Count", Value = 1 }]
        }
        ResultPath = null
        Next       = "ML_RECOVERY_FAILED"
      }
      COMPLETE           = { Type = "Succeed" }
      RTMS_FAILED        = { Type = "Fail", Error = "RTMS_FAILURE", Cause = "RTMS orchestration failed; ML restoration completed when required." }
      ML_RECOVERY_FAILED = { Type = "Fail", Error = "ML_RECOVERY_CRITICAL", Cause = "Captured ML task definition or desired count could not be restored." }
    }
  } : null
}

resource "aws_iam_role" "rtms_orchestration" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-rtms-orchestration"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "states.amazonaws.com" }
      Condition = { StringEquals = {
        "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        "aws:SourceArn"     = "arn:aws:states:${var.aws_region}:${data.aws_caller_identity.current.account_id}:stateMachine:${local.name}-rtms-refresh"
      } }
    }]
  })
  tags = { Service = "property-batch" }
}

resource "aws_iam_role_policy" "rtms_orchestration" {
  count = local.data_enabled ? 1 : 0
  name  = "orchestrate-reviewed-rtms-refresh"
  role  = aws_iam_role.rtms_orchestration[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "ReadExactBudgetCluster"
        Effect    = "Allow"
        Action    = ["ecs:ListTasks", "ecs:ListContainerInstances", "ecs:DescribeContainerInstances", "ecs:DescribeTasks"]
        Resource  = "*"
        Condition = { ArnEquals = { "ecs:cluster" = local.rtms_cluster_arn } }
      },
      {
        Sid      = "ReadAndUpdateExactMlService"
        Effect   = "Allow"
        Action   = ["ecs:DescribeServices", "ecs:UpdateService"]
        Resource = [local.rtms_ml_service_arn]
      },
      {
        Sid       = "RunExactRtmsRevision"
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = [var.rtms_refresh_task_definition_arn]
        Condition = { ArnEquals = { "ecs:cluster" = local.rtms_cluster_arn } }
      },
      {
        Sid      = "StopOnlyOrchestratedClusterTasks"
        Effect   = "Allow"
        Action   = ["ecs:StopTask"]
        Resource = ["arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task/${local.name}/*"]
      },
      {
        Sid       = "PassExactRtmsRoles"
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = [aws_iam_role.task_execution["rtms-daily-refresh"].arn, aws_iam_role.task_runtime["rtms-daily-refresh"].arn]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
      {
        Sid      = "ManageStepFunctionsEcsCompletionRule"
        Effect   = "Allow"
        Action   = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
        Resource = ["arn:aws:events:${var.aws_region}:${data.aws_caller_identity.current.account_id}:rule/StepFunctionsGetEventsForECSTaskRule"]
      },
      {
        Sid       = "EmitRtmsFailureMetrics"
        Effect    = "Allow"
        Action    = ["cloudwatch:PutMetricData"]
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = local.rtms_failure_namespace } }
      },
    ]
  })
}

resource "aws_sfn_state_machine" "rtms_refresh" {
  count      = local.data_enabled ? 1 : 0
  name       = "${local.name}-rtms-refresh"
  role_arn   = aws_iam_role.rtms_orchestration[0].arn
  definition = jsonencode(local.rtms_refresh_definition)
  type       = "STANDARD"
  tags       = { Service = "property-batch" }
}

resource "aws_cloudwatch_metric_alarm" "rtms_refresh_failure" {
  count               = local.data_enabled ? 1 : 0
  alarm_name          = "${local.name}-rtms-refresh-failure"
  alarm_description   = "RTMS refresh was skipped, timed out, lacked capacity, or exited non-zero."
  namespace           = local.rtms_failure_namespace
  metric_name         = "RtmsRefreshFailure"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ml_recovery_critical" {
  count               = local.data_enabled ? 1 : 0
  alarm_name          = "${local.name}-ml-recovery-critical"
  alarm_description   = "RTMS orchestration could not restore the captured ML revision and desired count."
  namespace           = local.rtms_failure_namespace
  metric_name         = "MlRecoveryCritical"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}
