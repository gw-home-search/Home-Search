locals {
  certificate_passphrase_parameter = "/home-search/budget-production/edge/certificate-passphrase"
  edge_script_base64 = local.foundation_enabled ? base64encode(templatefile("${path.module}/files/configure-edge.sh.tftpl", {
    certificate_arn      = aws_acm_certificate.public[0].arn
    passphrase_parameter = local.certificate_passphrase_parameter
    public_hostname      = var.public_hostname
  })) : ""
}

resource "aws_ssm_document" "configure_edge" {
  count           = local.foundation_enabled ? 1 : 0
  name            = "home-search-budget-production-configure-edge"
  document_type   = "Command"
  document_format = "JSON"
  target_type     = "/AWS::EC2::Instance"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Export ACM certificate without logging key material and atomically configure the budget edge."
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "configureEdge"
      inputs = {
        timeoutSeconds = "900"
        runCommand = [
          "umask 077",
          "printf '%s' '${local.edge_script_base64}' | base64 --decode >/var/tmp/configure-home-search-edge",
          "chmod 0500 /var/tmp/configure-home-search-edge",
          "/var/tmp/configure-home-search-edge",
          "rm -f /var/tmp/configure-home-search-edge",
        ]
      }
    }]
  })
  tags = { Service = "edge" }
}

resource "aws_ssm_association" "configure_edge" {
  count            = local.public_enabled ? 1 : 0
  name             = aws_ssm_document.configure_edge[0].name
  association_name = "home-search-budget-production-configure-edge"
  targets {
    key    = "InstanceIds"
    values = [aws_instance.host[0].id]
  }
  wait_for_success_timeout_seconds = 900
  depends_on                       = [aws_acm_certificate_validation.public, aws_eip_association.public]
}

resource "aws_iam_role" "certificate_renewal" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-certificate-renewal"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "events.amazonaws.com" }
    }]
  })
  tags = { Service = "edge" }
}

resource "aws_iam_role_policy" "certificate_renewal" {
  count = local.foundation_enabled ? 1 : 0
  name  = "send-reviewed-edge-document"
  role  = aws_iam_role.certificate_renewal[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["ssm:SendCommand"]
      Resource = [
        aws_ssm_document.configure_edge[0].arn,
        "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:instance/${aws_instance.host[0].id}",
      ]
    }]
  })
}

resource "aws_cloudwatch_event_rule" "certificate_available" {
  count       = local.foundation_enabled ? 1 : 0
  name        = "${local.name}-certificate-available"
  description = "Re-export an ACM certificate after issuance or managed renewal."
  event_pattern = jsonencode({
    source      = ["aws.acm"]
    detail-type = ["ACM Certificate Available"]
    resources   = [aws_acm_certificate.public[0].arn]
  })
  tags = { Service = "edge" }
}

resource "aws_cloudwatch_event_target" "certificate_available" {
  count     = local.public_enabled ? 1 : 0
  rule      = aws_cloudwatch_event_rule.certificate_available[0].name
  target_id = "configure-budget-edge"
  arn       = aws_ssm_document.configure_edge[0].arn
  role_arn  = aws_iam_role.certificate_renewal[0].arn
  run_command_targets {
    key    = "InstanceIds"
    values = [aws_instance.host[0].id]
  }
}
