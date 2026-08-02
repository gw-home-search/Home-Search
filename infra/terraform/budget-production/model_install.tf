locals {
  f37_model_version   = "deployment__F37_monthly_anchor_prev3_rolling_huber_010"
  f37_model_prefix    = "models/f37/${local.f37_model_version}/"
  f37_manifest_sha256 = filesha256("${path.module}/../../deploy/f37-model-manifest.json")
}

resource "aws_ssm_document" "install_ml_model" {
  count           = local.foundation_enabled ? 1 : 0
  name            = "${local.name}-install-ml-model"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Install the immutable allowlisted F37 model without exposing model bytes in SSM."
    parameters = {
      modelVersion   = { type = "String", allowedPattern = "^deployment__F37_monthly_anchor_prev3_rolling_huber_010$" }
      s3Prefix       = { type = "String", allowedPattern = "^models/f37/deployment__F37_monthly_anchor_prev3_rolling_huber_010/$" }
      manifestSha256 = { type = "String", allowedPattern = "^[0-9a-f]{64}$" }
    }
    mainSteps = [{
      action = "aws:runShellScript", name = "installModel"
      inputs = { timeoutSeconds = "900", runCommand = [
        "set -Eeuo pipefail",
        "test '{{ modelVersion }}' = '${local.f37_model_version}'",
        "test '{{ s3Prefix }}' = '${local.f37_model_prefix}'",
        "test '{{ manifestSha256 }}' = '${local.f37_manifest_sha256}'",
        "cluster='${local.name}'",
        "test \"$(aws ecs describe-services --cluster \"$cluster\" --services ml --query 'services[0].desiredCount' --output text)\" = 0",
        "workdir=$(mktemp -d /srv/home-search/runtime/.ml-download.XXXXXX)",
        "trap 'find \"$workdir\" -depth -delete 2>/dev/null || true' EXIT",
        "aws s3 cp 's3://${aws_s3_bucket.backup[0].id}/${local.f37_model_prefix}' \"$workdir\" --recursive --only-show-errors",
        "test \"$(find \"$workdir\" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')\" = 8",
        "test \"$(find \"$workdir\" -mindepth 1 -maxdepth 1 ! -type f | wc -l | tr -d ' ')\" = 0",
        "for name in manifest.json _SUCCESS eval_metrics.csv feature_schema.json keras_model.keras metadata.json numeric_medians.json sample_input.json; do test -f \"$workdir/$name\" && test ! -L \"$workdir/$name\"; done",
        "if command -v sha256sum >/dev/null 2>&1; then manifest_sha256=$(sha256sum \"$workdir/manifest.json\" | awk '{print $1}'); elif command -v shasum >/dev/null 2>&1; then manifest_sha256=$(shasum -a 256 \"$workdir/manifest.json\" | awk '{print $1}'); else echo '상태: Fail - SHA-256 checksum command가 없습니다.' >&2; exit 127; fi",
        "test \"$manifest_sha256\" = '{{ manifestSha256 }}'",
        "printf '%s' '${base64encode(file("${path.module}/../../deploy/install-ml-model.sh"))}' | base64 -d >\"$workdir/install.sh\"",
        "chmod 0500 \"$workdir/install.sh\"",
        "mkdir \"$workdir/artifact\"",
        "for name in _SUCCESS eval_metrics.csv feature_schema.json keras_model.keras metadata.json numeric_medians.json sample_input.json; do mv \"$workdir/$name\" \"$workdir/artifact/$name\"; done",
        "\"$workdir/install.sh\" \"$workdir/manifest.json\" \"$workdir/artifact\" /srv/home-search/runtime/ml-model 10001:10001",
        "docker pull '${var.image_uris["ml"]}' >/dev/null",
        "docker run --rm --log-driver none --user 10001:10001 -e F37_ARTIFACT_DIR=/model -v /srv/home-search/runtime/ml-model:/model:ro '${var.image_uris["ml"]}' python -m ml_service.smoke_predict >/tmp/home-search-ml-smoke.json",
        "python3 -c 'import json,math; p=json.load(open(\"/tmp/home-search-ml-smoke.json\")); v=float(p[\"predictedPricePerM2\"]); assert math.isfinite(v) and v > 0; assert p[\"modelVersion\"] == \"${local.f37_model_version}\"'",
        "rm -f /tmp/home-search-ml-smoke.json",
      ] }
    }]
  })
  tags = { Service = "ml", DataClass = "internal" }
}
