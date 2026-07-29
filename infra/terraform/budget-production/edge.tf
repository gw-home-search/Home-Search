resource "aws_acm_certificate" "public" {
  count             = local.foundation_enabled ? 1 : 0
  domain_name       = var.public_hostname
  validation_method = "DNS"
  options { export = "ENABLED" }
  lifecycle {
    create_before_destroy = true
    prevent_destroy       = true
  }
  tags = { Service = "edge", Exportable = "true" }
}

resource "aws_route53_record" "certificate_validation" {
  for_each = local.foundation_enabled ? { public = var.public_hostname } : {}
  zone_id  = var.hosted_zone_id
  name     = one(aws_acm_certificate.public[0].domain_validation_options).resource_record_name
  type     = one(aws_acm_certificate.public[0].domain_validation_options).resource_record_type
  records  = [one(aws_acm_certificate.public[0].domain_validation_options).resource_record_value]
  ttl      = 300
}

resource "aws_acm_certificate_validation" "public" {
  count                   = local.foundation_enabled ? 1 : 0
  certificate_arn         = aws_acm_certificate.public[0].arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}

resource "aws_route53_record" "public" {
  count   = local.public_enabled && var.public_dns_enabled ? 1 : 0
  zone_id = var.hosted_zone_id
  name    = var.public_hostname
  type    = "A"
  ttl     = 60
  records = [aws_eip.public[0].public_ip]
}
