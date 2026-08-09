mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

override_resource {
  target          = aws_kms_key.terraform_state
  override_during = plan
  values = {
    arn = "arn:aws:kms:ap-northeast-2:123456789012:key/11111111-1111-1111-1111-111111111111"
  }
}

override_resource {
  target          = aws_s3_bucket.terraform_state
  override_during = plan
  values = {
    arn = "arn:aws:s3:::home-search-state-fixture-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}

run "budget_roles_are_separated_and_state_isolated" {
  command = plan
  variables {
    state_bucket_name                = "home-search-state-fixture-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    github_repository                = "example/home-search"
    budget_production_hosted_zone_id = "ZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  }

  assert {
    condition = (
      aws_iam_role.github_budget_production_plan.name == "home-search-github-budget-production-plan"
      && aws_iam_role.github_budget_production_apply.name == "home-search-github-budget-production-apply"
      && aws_iam_role.github_budget_production_deploy.name == "home-search-github-budget-production-deploy"
    )
    error_message = "Budget plan, apply, and deploy must use distinct OIDC roles."
  }

  assert {
    condition = (
      var.budget_production_state_key == "home-search/budget-production/terraform.tfstate"
      && !contains(local.budget_state_object_actions.plan, "s3:PutObject")
      && contains(local.budget_state_object_actions.apply, "s3:PutObject")
      && contains(local.budget_state_lock_actions, "s3:DeleteObject")
      && contains(local.budget_forbidden_state_keys, "home-search/production/terraform.tfstate")
      && contains(local.budget_forbidden_state_keys, "home-search/staging/terraform.tfstate")
      && contains(local.budget_forbidden_state_keys, "home-search/bootstrap/terraform.tfstate")
      && !contains(local.budget_apply_actions, "s3:PutBucketPolicy")
      && !contains(local.budget_apply_actions, "s3:PutObject")
    )
    error_message = "Budget roles must access only the budget state object and native lockfile."
  }

  assert {
    condition = (
      contains(local.budget_state_lock_actions, "s3:DeleteObject")
      && !contains(local.budget_apply_explicit_deny_actions, "s3:DeleteObject")
      && !contains(local.budget_apply_actions, "s3:DeleteObject")
    )
    error_message = "Budget apply must be able to release its exact native state lock without granting broad state deletion."
  }

  assert {
    condition = (
      one(local.github_budget_plan_oidc_string_equals["token.actions.githubusercontent.com:environment"]) == "budget-production-plan"
      && one(local.github_budget_apply_oidc_string_equals["token.actions.githubusercontent.com:environment"]) == "budget-production"
      && toset(local.github_budget_plan_oidc_string_equals["token.actions.githubusercontent.com:workflow"]) == toset([
        "Deploy budget production",
        "Rollout budget production",
      ])
      && toset(local.github_budget_apply_oidc_string_equals["token.actions.githubusercontent.com:workflow"]) == toset([
        "Deploy budget production",
        "Rollout budget production",
      ])
    )
    error_message = "Budget OIDC trust must bind the exact plan/apply environments and workflow."
  }

  assert {
    condition = (
      !contains(local.budget_read_actions, "ssm:GetParameter")
      && contains(local.budget_read_actions, "budgets:ViewBudget")
      && contains(local.budget_read_actions, "budgets:ListTagsForResource")
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_plan.policy).Statement :
        statement.Sid == "ReadPublicEcsOptimizedAmi"
        && statement.Action == ["ssm:GetParameter"]
        && statement.Resource == ["arn:aws:ssm:ap-northeast-2::parameter/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"]
      ])
    )
    error_message = "Budget plan must include provider refresh reads while keeping SSM GetParameter scoped to the exact public AMI parameter."
  }

  assert {
    condition = alltrue([
      for policy in [
        aws_iam_role_policy.github_budget_plan.policy,
        aws_iam_role_policy.github_budget_apply.policy,
        ] : anytrue([
          for statement in jsondecode(policy).Statement :
          statement.Sid == "ReadBudgetBucketProviderMetadata"
          && statement.Action == [
            "s3:GetAccelerateConfiguration",
            "s3:GetReplicationConfiguration",
          ]
          && statement.Resource == [
            "arn:aws:s3:::home-search-budget-production-backup-123456789012",
            "arn:aws:s3:::home-search-budget-production-reference-raw-123456789012",
          ]
      ])
    ])
    error_message = "Plan/apply must scope the provider-required accelerate and replication metadata reads to the two budget-production buckets."
  }

  assert {
    condition = alltrue([
      for policy in [
        aws_iam_role_policy.github_budget_plan.policy,
        aws_iam_role_policy.github_budget_apply.policy,
        ] : anytrue([
          for statement in jsondecode(policy).Statement :
          statement.Sid == "ReadBudgetSecretContainersForProviderRefresh"
          && statement.Action == ["ssm:GetParameter"]
          && statement.Resource == ["arn:aws:ssm:ap-northeast-2:123456789012:parameter/home-search/budget-production/*"]
      ])
    ])
    error_message = "Plan/apply must scope the provider-required SecureString refresh to the budget-production parameter prefix."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_role_policy.github_budget_plan.policy).Statement :
      statement.Sid == "ReadReviewedBootstrapEvidence"
      && statement.Action == ["s3:GetObject"]
      && statement.Resource == ["arn:aws:s3:::home-search-budget-production-backup-123456789012/deployment-evidence/bootstrap/*/terraform-bootstrap-plan.json"]
    ])
    error_message = "Budget plan may read only reviewed bootstrap evidence from the exact release-scoped prefix."
  }

  assert {
    condition = (
      alltrue([
        for action in [
          "ec2:AssociateRouteTable",
          "ec2:AttachInternetGateway",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:DetachInternetGateway",
          "ec2:DisassociateRouteTable",
          "ec2:RevokeSecurityGroupEgress",
          "logs:DeleteMetricFilter",
          "logs:PutMetricFilter",
        ] : contains(local.budget_apply_actions, action)
      ])
      && alltrue([
        for action in [
          "budgets:CreateBudget",
          "budgets:DeleteBudget",
          "budgets:ModifyBudget",
          "ce:CreateAnomalyMonitor",
          "ce:CreateAnomalySubscription",
          "ce:DeleteAnomalyMonitor",
          "ce:DeleteAnomalySubscription",
          "ce:UpdateAnomalyMonitor",
          "ce:UpdateAnomalySubscription",
          "route53:ChangeResourceRecordSets",
        ] : !contains(local.budget_apply_actions, action)
      ])
    )
    error_message = "Regional mutations must include provider CRUD actions while global services remain outside the RequestedRegion-gated action set."
  }

  assert {
    condition = (
      !contains(local.budget_apply_actions, "ssm:UpdateDocument")
      && !contains(local.budget_apply_actions, "ssm:UpdateDocumentDefaultVersion")
      && aws_iam_role_policy_attachment.github_budget_apply_ssm_documents.role == aws_iam_role.github_budget_production_apply.name
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_ssm_documents.policy).Statement :
        statement.Sid == "ManageBudgetMlModelInstallerDocument"
        && toset(statement.Action) == toset([
          "ssm:UpdateDocument",
          "ssm:UpdateDocumentDefaultVersion",
        ])
        && statement.Resource == ["arn:aws:ssm:ap-northeast-2:123456789012:document/home-search-budget-production-install-ml-model"]
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_ssm_documents.policy).Statement :
        statement.Sid == "DenyCrossEnvironmentSsmDocumentMutation"
        && toset(statement.Action) == toset([
          "ssm:UpdateDocument",
          "ssm:UpdateDocumentDefaultVersion",
        ])
        && statement.NotResource == ["arn:aws:ssm:ap-northeast-2:123456789012:document/home-search-budget-production-install-ml-model"]
      ])
    )
    error_message = "Budget apply must set the reviewed SSM document default version while retaining the cross-environment explicit deny boundary."
  }

  assert {
    condition = (
      contains(local.budget_read_actions, "scheduler:GetSchedule")
      && contains(local.budget_read_actions, "scheduler:GetScheduleGroup")
      && contains(local.budget_read_actions, "scheduler:ListTagsForResource")
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_schedules.policy).Statement :
        statement.Sid == "ManageBudgetBackupScheduleGroup"
        && toset(statement.Action) == toset([
          "scheduler:CreateScheduleGroup",
          "scheduler:DeleteScheduleGroup",
          "scheduler:TagResource",
          "scheduler:UntagResource",
        ])
        && statement.Resource == ["arn:aws:scheduler:ap-northeast-2:123456789012:schedule-group/home-search-budget-production-backup"]
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_schedules.policy).Statement :
        statement.Sid == "ManageBudgetLogicalBackupSchedule"
        && toset(statement.Action) == toset([
          "scheduler:CreateSchedule",
          "scheduler:DeleteSchedule",
          "scheduler:UpdateSchedule",
        ])
        && statement.Resource == ["arn:aws:scheduler:ap-northeast-2:123456789012:schedule/home-search-budget-production-backup/home-search-budget-production-logical-backup"]
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_schedules.policy).Statement :
        statement.Sid == "ManageBudgetRuntimeScheduleGroups"
        && length(statement.Resource) == 2
        && alltrue([for arn in statement.Resource : startswith(arn, "arn:aws:scheduler:ap-northeast-2:123456789012:schedule-group/home-search-budget-production-")])
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_schedules.policy).Statement :
        statement.Sid == "ManageBudgetRuntimeSchedules"
        && length(statement.Resource) == 5
        && alltrue([for arn in statement.Resource : startswith(arn, "arn:aws:scheduler:ap-northeast-2:123456789012:schedule/home-search-budget-production-")])
      ])
      && aws_iam_role_policy_attachment.github_budget_apply_schedules.role == aws_iam_role.github_budget_production_apply.name
    )
    error_message = "Budget apply must manage only the reviewed backup, market-news, and RTMS schedules and their exact schedule groups."
  }

  assert {
    condition = (
      toset(keys(aws_ssm_parameter.budget_production_external)) == toset([
        "property/news/naver-client-id",
        "property/news/naver-client-secret",
      ])
      && alltrue([for parameter in values(aws_ssm_parameter.budget_production_external) : parameter.type == "SecureString"])
    )
    error_message = "Bootstrap must create only the two news provider SecureString containers needed before rollout."
  }

  assert {
    condition = (
      anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetCostControls"
        && contains(statement.Action, "budgets:ModifyBudget")
        && statement.Resource == ["arn:aws:budgets::123456789012:budget/home-search-budget-production-monthly"]
        && !contains(keys(statement), "Condition")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "CreateBudgetCostAnomalySubscription"
        && contains(statement.Action, "ce:CreateAnomalySubscription")
        && !contains(statement.Action, "ce:CreateAnomalyMonitor")
        && statement.Resource == "*"
        && statement.Condition.StringEquals["aws:RequestTag/Environment"] == "budget-production"
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetCostAnomalySubscription"
        && statement.Resource == ["arn:aws:ce::123456789012:anomalysubscription/*"]
        && !contains(statement.Action, "ce:DeleteAnomalyMonitor")
        && !contains(statement.Action, "ce:UpdateAnomalyMonitor")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetHostedZoneRecords"
        && statement.Action == ["route53:ChangeResourceRecordSets"]
        && statement.Resource == ["arn:aws:route53:::hostedzone/ZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"]
        && !contains(keys(statement), "Condition")
      ])
    )
    error_message = "Budgets, Cost Explorer, and Route53 must use explicit global-service statements without a regional condition."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
      !contains(["CreateBudgetsServiceLinkedRole", "CreateCloudWatchEventsServiceLinkedRole"], statement.Sid)
    ])
    error_message = "Service-linked role permissions must leave the aggregate inline policy budget with safe headroom."
  }

  assert {
    condition = (
      length(jsondecode(aws_iam_policy.github_budget_apply_service_linked_roles.policy).Statement) == 2
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_service_linked_roles.policy).Statement :
        statement.Sid == "CreateBudgetsServiceLinkedRole"
        && statement.Action == ["iam:CreateServiceLinkedRole"]
        && statement.Resource == ["arn:aws:iam::123456789012:role/aws-service-role/budgets.amazonaws.com/AWSServiceRoleForBudgets"]
        && statement.Condition.StringEquals["iam:AWSServiceName"] == "budgets.amazonaws.com"
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_policy.github_budget_apply_service_linked_roles.policy).Statement :
        statement.Sid == "CreateCloudWatchEventsServiceLinkedRole"
        && statement.Action == ["iam:CreateServiceLinkedRole"]
        && statement.Resource == ["arn:aws:iam::123456789012:role/aws-service-role/events.amazonaws.com/AWSServiceRoleForCloudWatchEvents*"]
        && statement.Condition.StringLike["iam:AWSServiceName"] == "events.amazonaws.com"
      ])
      && aws_iam_role_policy_attachment.github_budget_apply_service_linked_roles.role == aws_iam_role.github_budget_production_apply.name
    )
    error_message = "The budget apply role must receive only the exact approved service-linked role permissions through its dedicated managed policy."
  }

  assert {
    condition = (
      anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_deploy.policy).Statement :
        statement.Sid == "ReadBudgetAiCanaryLogs"
        && statement.Action == ["logs:GetLogEvents"]
        && statement.Resource == ["arn:aws:logs:ap-northeast-2:123456789012:log-group:/home-search/budget-production/ai:log-stream:*"]
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_deploy.policy).Statement :
        statement.Sid == "InstallModelOnBudgetHostOnly"
        && statement.Condition.StringEquals["ssm:resourceTag/Environment"] == "budget-production"
        && statement.Condition.StringEquals["ssm:resourceTag/Service"] == "host"
      ])
    )
    error_message = "Budget deploy must scope AI canary log reads and F37 installation to the exact approved runtime resources."
  }

  assert {
    condition = (
      anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetRoles"
        && contains(statement.Action, "iam:TagInstanceProfile")
        && contains(statement.Action, "iam:UntagInstanceProfile")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetBucketsOnly"
        && contains(statement.Action, "s3:PutBucketObjectLockConfiguration")
        && contains(statement.Action, "s3:PutBucketTagging")
        && contains(statement.Action, "s3:PutLifecycleConfiguration")
        && !contains(statement.Action, "s3:PutBucketLifecycleConfiguration")
      ])
    )
    error_message = "Foundation apply must include the instance-profile and protected-bucket tag/Object Lock APIs used by the provider."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
      statement.Sid == "DenyCrossEnvironmentEc2Mutation"
      && toset(statement.Resource) == toset([
        "arn:aws:ec2:ap-northeast-2:123456789012:elastic-ip/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:instance/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:internet-gateway/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:route-table/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:security-group/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:subnet/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:volume/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:vpc/*",
      ])
      && alltrue([
        for action in [
          "ec2:AssociateRouteTable",
          "ec2:AttachInternetGateway",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:DetachInternetGateway",
          "ec2:DisassociateRouteTable",
          "ec2:RevokeSecurityGroupEgress",
        ] : contains(statement.Action, action)
      ])
    ])
    error_message = "Every added EC2 connection and egress mutation must retain the cross-environment explicit deny boundary."
  }

  assert {
    condition = (
      anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "DenyCrossEnvironmentControlPlaneMutation"
        && contains(statement.NotResource, "arn:aws:ec2:ap-northeast-2:123456789012:instance/*")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "DenyNonBudgetSsmAssociationTarget"
        && toset(statement.Action) == toset(["ssm:CreateAssociation", "ssm:UpdateAssociation"])
        && statement.Resource == ["arn:aws:ec2:ap-northeast-2:123456789012:instance/*"]
        && statement.Condition.StringNotEqualsIfExists["aws:ResourceTag/Environment"] == "budget-production"
      ])
    )
    error_message = "SSM association authorization must admit the tagged budget host instance resource while explicitly denying foreign or untagged instance targets."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
      statement.Sid == "DenyNonBudgetHostType"
      && statement.Action == ["ec2:RunInstances"]
      && statement.Resource == ["arn:aws:ec2:ap-northeast-2:123456789012:instance/*"]
      && statement.Condition.StringNotEquals["ec2:InstanceType"] == "t3a.large"
    ])
    error_message = "The instance-type deny must target only the instance resource so RunInstances dependency resources are not denied when ec2:InstanceType is absent."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
      statement.Sid == "DenyNonBudgetEc2CreateTags"
      && toset(statement.Action) == toset([
        "ec2:AuthorizeSecurityGroupEgress",
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:RunInstances",
      ])
      && toset(statement.Resource) == toset([
        "arn:aws:ec2:ap-northeast-2:123456789012:instance/*",
        "arn:aws:ec2:ap-northeast-2:123456789012:security-group-rule/*",
      ])
      && statement.Condition.StringNotEquals["aws:RequestTag/Environment"] == "budget-production"
    ])
    error_message = "New budget hosts and security-group rules must carry the budget-production request tag without applying resource-tag conditions before creation."
  }

  assert {
    condition = (
      !contains(keys(local.budget_state_role_ids), "deploy")
      && !contains(local.budget_deploy_actions, "ec2:ModifyInstanceCreditSpecification")
      && !contains(local.budget_deploy_actions, "ec2:CreateVolume")
      && !contains(local.budget_deploy_actions, "ec2:AttachVolume")
      && !contains(local.budget_deploy_actions, "ec2:CreateSnapshot")
      && !contains(local.budget_deploy_actions, "ec2:TerminateInstances")
      && !contains(local.budget_deploy_actions, "ec2:RunInstances")
      && !contains(local.budget_deploy_actions, "iam:PassRole")
      && !contains(local.budget_deploy_actions, "s3:GetObject")
      && !contains(local.budget_deploy_actions, "s3:PutObject")
      && !contains(local.budget_deploy_actions, "ecs:RunTask")
      && !contains(local.budget_deploy_actions, "ecs:StopTask")
      && !contains(local.budget_deploy_actions, "ecs:UpdateService")
      && !contains(local.budget_deploy_actions, "ssm:SendCommand")
      && contains(local.budget_deploy_actions, "ecs:ListContainerInstances")
      && contains(local.budget_deploy_actions, "ecs:DescribeTasks")
    )
    error_message = "Deploy may use backup S3, but must not receive a Terraform state policy and must explicitly deny the budget state key."
  }

  assert {
    condition = (
      length(aws_iam_role_policy.github_budget_apply.policy)
      + length(aws_iam_role_policy.github_budget_state["apply"].policy) <= 10000
      && length(aws_iam_policy.github_budget_apply_regional.policy) <= 6144
      && length(aws_iam_policy.github_budget_apply_service_linked_roles.policy) <= 6144
      && length(aws_iam_policy.github_budget_apply_schedules.policy) <= 6144
      && length(aws_iam_policy.github_budget_apply_ssm_documents.policy) <= 6144
      && length(aws_iam_policy.github_budget_step_functions_read.policy) <= 6144
      && length(aws_iam_policy.github_budget_apply_step_functions.policy) <= 6144
      && aws_iam_role_policy_attachment.github_budget_apply_regional.role == aws_iam_role.github_budget_production_apply.name
      && aws_iam_role_policy_attachment.github_budget_plan_step_functions_read.role == aws_iam_role.github_budget_production_plan.name
      && aws_iam_role_policy_attachment.github_budget_apply_step_functions_read.role == aws_iam_role.github_budget_production_apply.name
      && aws_iam_role_policy_attachment.github_budget_apply_step_functions.role == aws_iam_role.github_budget_production_apply.name
      && one([
        for statement in jsondecode(aws_iam_policy.github_budget_step_functions_read.policy).Statement : statement
        if statement.Sid == "ReadExactRtmsStateMachine"
      ]).Resource == [local.budget_rtms_state_machine_arn]
      && one([
        for statement in jsondecode(aws_iam_policy.github_budget_step_functions_read.policy).Statement : statement
        if statement.Sid == "ValidateRtmsStateMachineDefinition"
      ]).Resource == "*"
      && one(jsondecode(aws_iam_policy.github_budget_apply_step_functions.policy).Statement).Resource == [local.budget_rtms_state_machine_arn]
      && one([
        for statement in jsondecode(aws_iam_policy.github_budget_step_functions_read.policy).Statement : statement
        if statement.Sid == "ReadExactRtmsStateMachine"
      ]).Action == ["states:DescribeStateMachine", "states:ListTagsForResource"]
      && one([
        for statement in jsondecode(aws_iam_policy.github_budget_step_functions_read.policy).Statement : statement
        if statement.Sid == "ValidateRtmsStateMachineDefinition"
      ]).Action == ["states:ValidateStateMachineDefinition"]
      && one(jsondecode(aws_iam_policy.github_budget_apply_step_functions.policy).Statement).Action == ["states:CreateStateMachine", "states:DeleteStateMachine", "states:TagResource", "states:UntagResource", "states:UpdateStateMachine"]
      && one(jsondecode(aws_iam_policy.github_budget_apply_regional.policy).Statement).Sid == "ManageTaggedBudgetResources"
      && one(jsondecode(aws_iam_policy.github_budget_apply_regional.policy).Statement).Action == local.budget_apply_actions
      && one(jsondecode(aws_iam_policy.github_budget_apply_regional.policy).Statement).Condition.StringEqualsIfExists["aws:RequestedRegion"] == "ap-northeast-2"
      && alltrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid != "ManageTaggedBudgetResources"
      ])
    )
    error_message = "Budget apply inline aggregate and regional managed policy must stay within AWS policy limits and preserve the regional condition."
  }

  assert {
    condition     = length(aws_iam_role_policy.github_budget_deploy.policy) <= 10240
    error_message = "Budget deploy inline IAM policy exceeds the AWS 10,240-byte role policy limit."
  }
}
