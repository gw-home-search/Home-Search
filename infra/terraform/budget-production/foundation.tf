resource "aws_vpc" "this" {
  count                = local.foundation_enabled ? 1 : 0
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = "${local.name}-vpc" }
}

resource "aws_internet_gateway" "this" {
  count  = local.foundation_enabled ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  tags   = { Name = "${local.name}-igw" }
}

resource "aws_subnet" "public" {
  count                   = local.foundation_enabled ? 1 : 0
  vpc_id                  = aws_vpc.this[0].id
  cidr_block              = var.subnet_cidr
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-public-${var.availability_zone}" }
}

resource "aws_route_table" "public" {
  count  = local.foundation_enabled ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  tags   = { Name = "${local.name}-public" }
}

resource "aws_route" "internet" {
  count                  = local.foundation_enabled ? 1 : 0
  route_table_id         = aws_route_table.public[0].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this[0].id
}

resource "aws_route_table_association" "public" {
  count          = local.foundation_enabled ? 1 : 0
  subnet_id      = aws_subnet.public[0].id
  route_table_id = aws_route_table.public[0].id
}

resource "aws_security_group" "host" {
  count       = local.foundation_enabled ? 1 : 0
  name        = "${local.name}-host"
  description = "Budget production public host; no SSH, database, cache, or admin ingress"
  vpc_id      = aws_vpc.this[0].id
  dynamic "egress" {
    for_each = local.host_egress
    content {
      cidr_blocks = ["0.0.0.0/0"]
      protocol    = egress.value.protocol
      from_port   = egress.value.port
      to_port     = egress.value.port
      description = egress.value.description
    }
  }
  tags = { Name = "${local.name}-host" }
}

locals { public_ingress_ports = toset(["80", "443"]) }

resource "aws_vpc_security_group_ingress_rule" "public" {
  for_each          = local.foundation_enabled ? local.public_ingress_ports : toset([])
  security_group_id = aws_security_group.host[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  description       = each.value == "443" ? "Public HTTPS" : "HTTP to HTTPS redirect"
}

locals {
  host_egress = local.foundation_enabled ? {
    https = { protocol = "tcp", port = 443, description = "AWS and reviewed provider HTTPS endpoints" }
    dns-t = { protocol = "tcp", port = 53, description = "DNS TCP" }
    dns-u = { protocol = "udp", port = 53, description = "DNS UDP" }
    ntp   = { protocol = "udp", port = 123, description = "Amazon Time Sync fallback" }
  } : {}
}

removed {
  from = aws_vpc_security_group_egress_rule.host
  lifecycle {
    destroy = false
  }
}

resource "aws_eip" "public" {
  count  = local.foundation_enabled ? 1 : 0
  domain = "vpc"
  tags   = { Name = "${local.name}-public" }
}

resource "aws_iam_role" "host" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-host"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
  tags = { Service = "host" }
}

resource "aws_iam_role_policy_attachment" "host_ecs" {
  count      = local.foundation_enabled ? 1 : 0
  role       = aws_iam_role.host[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

resource "aws_iam_role_policy_attachment" "host_ssm" {
  count      = local.foundation_enabled ? 1 : 0
  role       = aws_iam_role.host[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "host_operations" {
  count = local.foundation_enabled ? 1 : 0
  name  = "budget-production-host-operations"
  role  = aws_iam_role.host[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadCertificatePassphrase"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter"]
        Resource = ["arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/home-search/budget-production/edge/certificate-passphrase"]
      },
      {
        Sid      = "ExportPublicCertificate"
        Effect   = "Allow"
        Action   = ["acm:DescribeCertificate", "acm:ExportCertificate"]
        Resource = [aws_acm_certificate.public[0].arn]
      },
      {
        Sid      = "ReadBackupAge"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.backup[0].arn]
        Condition = {
          StringLike = { "s3:prefix" = ["logical", "logical/*"] }
        }
      },
      {
        Sid      = "ReadReviewedF37Model"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = ["${aws_s3_bucket.backup[0].arn}/models/f37/deployment__F37_monthly_anchor_prev3_rolling_huber_010/*"]
      },
      {
        Sid      = "ListReviewedF37Model"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.backup[0].arn]
        Condition = { StringLike = {
          "s3:prefix" = ["models/f37/deployment__F37_monthly_anchor_prev3_rolling_huber_010/*"]
        } }
      },
      {
        Sid       = "DecryptReviewedF37Model"
        Effect    = "Allow"
        Action    = ["kms:Decrypt"]
        Resource  = ["arn:aws:kms:${var.aws_region}:${data.aws_caller_identity.current.account_id}:alias/aws/s3"]
        Condition = { StringEquals = { "kms:ViaService" = "s3.${var.aws_region}.amazonaws.com" } }
      },
      {
        Sid      = "DescribeAttachedVolume"
        Effect   = "Allow"
        Action   = ["ec2:DescribeInstances", "ec2:DescribeTags", "ec2:DescribeVolumes", "ecs:DescribeServices"]
        Resource = "*"
      },
      {
        Sid      = "WriteHostMetrics"
        Effect   = "Allow"
        Action   = ["cloudwatch:PutMetricData"]
        Resource = "*"
        Condition = {
          StringEquals = { "cloudwatch:namespace" = "HomeSearch/BudgetProduction" }
        }
      },
      {
        Sid      = "WriteHostNginxLog"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.host[0].arn}:*"]
      },
    ]
  })
}

resource "aws_iam_instance_profile" "host" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-host"
  role  = aws_iam_role.host[0].name
}

resource "aws_ecs_cluster" "this" {
  count = local.foundation_enabled ? 1 : 0
  name  = local.name
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
  tags = { Service = "ecs" }
}

resource "aws_ebs_volume" "data" {
  count             = local.foundation_enabled ? 1 : 0
  availability_zone = var.availability_zone
  type              = "gp3"
  size              = var.data_volume_size_gib
  iops              = 3000
  throughput        = 125
  encrypted         = true
  lifecycle { prevent_destroy = true }
  tags = {
    Name      = "${local.name}-data"
    DataClass = "restricted"
    Backup    = "daily"
  }
}

resource "aws_instance" "host" {
  count                                = local.foundation_enabled ? 1 : 0
  ami                                  = var.ami_id
  instance_type                        = var.instance_type
  availability_zone                    = var.availability_zone
  subnet_id                            = aws_subnet.public[0].id
  vpc_security_group_ids               = [aws_security_group.host[0].id]
  iam_instance_profile                 = aws_iam_instance_profile.host[0].name
  associate_public_ip_address          = false
  disable_api_termination              = true
  instance_initiated_shutdown_behavior = "stop"
  monitoring                           = false
  source_dest_check                    = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }
  credit_specification { cpu_credits = "standard" }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size_gib
    iops                  = 3000
    throughput            = 125
    encrypted             = true
    delete_on_termination = true
  }
  user_data = templatefile("${path.module}/files/host-bootstrap.sh.tftpl", {
    availability_zone = var.availability_zone
    cluster_name      = aws_ecs_cluster.this[0].name
    data_volume_id    = aws_ebs_volume.data[0].id
  })
  user_data_replace_on_change = false
  lifecycle {
    # The provider reports true after the retained EIP is associated; that observation must not replace the host.
    ignore_changes = [ami, user_data, associate_public_ip_address]
  }
  depends_on = [
    aws_iam_role_policy_attachment.host_ecs,
    aws_iam_role_policy_attachment.host_ssm,
    aws_iam_role_policy.host_operations,
  ]
  tags = { Name = "${local.name}-host", Service = "host" }
}

resource "aws_eip_association" "public" {
  count         = local.foundation_enabled ? 1 : 0
  allocation_id = aws_eip.public[0].id
  instance_id   = aws_instance.host[0].id
}

resource "aws_volume_attachment" "data" {
  count                          = local.foundation_enabled ? 1 : 0
  device_name                    = "/dev/sdf"
  volume_id                      = aws_ebs_volume.data[0].id
  instance_id                    = aws_instance.host[0].id
  force_detach                   = false
  skip_destroy                   = true
  stop_instance_before_detaching = true
}
