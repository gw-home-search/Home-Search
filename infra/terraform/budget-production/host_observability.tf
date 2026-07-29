locals {
  observability_script_base64 = local.foundation_enabled ? base64encode(templatefile("${path.module}/files/configure-observability.sh.tftpl", {
    aws_region    = var.aws_region
    backup_bucket = aws_s3_bucket.backup[0].id
    ecs_cluster   = aws_ecs_cluster.this[0].name
  })) : ""
}

resource "aws_ssm_document" "configure_observability" {
  count           = local.foundation_enabled ? 1 : 0
  name            = "home-search-budget-production-configure-observability"
  document_type   = "Command"
  document_format = "JSON"
  target_type     = "/AWS::EC2::Instance"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Configure low-cost host metrics and privacy-safe Nginx log collection."
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "configureObservability"
      inputs = {
        timeoutSeconds = "900"
        runCommand = [
          "umask 077",
          "printf '%s' '${local.observability_script_base64}' | base64 --decode >/var/tmp/configure-home-search-observability",
          "chmod 0500 /var/tmp/configure-home-search-observability",
          "/var/tmp/configure-home-search-observability",
          "rm -f /var/tmp/configure-home-search-observability",
        ]
      }
    }]
  })
  tags = { Service = "observability" }
}

resource "aws_ssm_association" "configure_observability" {
  count            = local.foundation_enabled ? 1 : 0
  name             = aws_ssm_document.configure_observability[0].name
  association_name = "home-search-budget-production-configure-observability"
  targets {
    key    = "InstanceIds"
    values = [aws_instance.host[0].id]
  }
  wait_for_success_timeout_seconds = 900
  depends_on                       = [aws_volume_attachment.data]
}
