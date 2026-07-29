resource "aws_security_group" "recovery" {
  count       = local.foundation_enabled ? 1 : 0
  name        = "${local.name}-recovery"
  description = "Ephemeral recovery rehearsal; intentionally no ingress"
  vpc_id      = aws_vpc.this[0].id
  tags        = { Service = "recovery", Ingress = "none" }
}

resource "aws_vpc_security_group_egress_rule" "recovery_https" {
  count             = local.foundation_enabled ? 1 : 0
  security_group_id = aws_security_group.recovery[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "SSM, ECR, S3, and CloudWatch HTTPS"
}

resource "aws_vpc_security_group_egress_rule" "recovery_dns_udp" {
  count             = local.foundation_enabled ? 1 : 0
  security_group_id = aws_security_group.recovery[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "udp"
  from_port         = 53
  to_port           = 53
  description       = "DNS UDP"
}

resource "aws_vpc_security_group_egress_rule" "recovery_dns_tcp" {
  count             = local.foundation_enabled ? 1 : 0
  security_group_id = aws_security_group.recovery[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 53
  to_port           = 53
  description       = "DNS TCP"
}

resource "aws_iam_role" "recovery" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-recovery"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
  tags = { Service = "recovery" }
}

resource "aws_iam_role_policy_attachment" "recovery_ssm" {
  count      = local.foundation_enabled ? 1 : 0
  role       = aws_iam_role.recovery[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "recovery" {
  count = local.foundation_enabled ? 1 : 0
  name  = "read-backups-write-run-evidence"
  role  = aws_iam_role.recovery[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthorization"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "PullRecoveryImages"
        Effect = "Allow"
        Action = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = [
          "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/home-search/backup",
          aws_ecr_repository.platform["budget-postgres"].arn,
        ]
      },
      {
        Sid      = "ListLogicalBackupsAndEvidence"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.backup[0].arn]
        Condition = {
          StringLike = { "s3:prefix" = ["logical", "logical/*", "restore-evidence", "restore-evidence/*"] }
        }
      },
      {
        Sid      = "ReadLogicalBackups"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = ["${aws_s3_bucket.backup[0].arn}/logical/*"]
      },
      {
        Sid      = "WriteOwnRestoreEvidence"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = ["${aws_s3_bucket.backup[0].arn}/restore-evidence/*"]
      },
      {
        Sid      = "WriteRecoveryCommandLog"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.recovery[0].arn}:*"]
      },
    ]
  })
}

resource "aws_iam_instance_profile" "recovery" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-recovery"
  role  = aws_iam_role.recovery[0].name
}
