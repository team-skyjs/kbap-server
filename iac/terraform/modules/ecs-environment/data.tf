data "aws_caller_identity" "current" {}

data "aws_vpc" "this" {
  filter {
    name   = "tag:Name"
    values = [var.vpc_name]
  }
}

data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.this.id]
  }
  filter {
    name   = "tag:Name"
    values = [var.public_subnet_name_pattern]
  }
}

data "aws_route53_zone" "this" {
  name         = var.hosted_zone_name
  private_zone = false
}

data "aws_acm_certificate" "this" {
  domain      = var.certificate_domain
  statuses    = ["ISSUED"]
  most_recent = true
}

# 컨테이너 인스턴스는 Graviton(t4g) — arm64 AMI. 태스크 정의의 runtime_platform(ARM64)·CI 의 arm64 이미지 빌드와 한 세트다.
data "aws_ssm_parameter" "ecs_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/image_id"
}

data "aws_sqs_queue" "food_content" {
  name = var.food_content_queue_name
}

data "aws_kms_alias" "ssm" {
  name = "alias/aws/ssm"
}

locals {
  name_prefix                   = "kbap-${var.env}-ecs"
  fqdn                          = "${var.subdomain}.${var.hosted_zone_name}"
  ssm_prefix                    = "/kbap/${var.env}"
  performance_profiling_enabled = var.env == "dev" && var.api_execute_command_enabled

  common_tags = {
    Environment = var.env
    Platform    = "ecs"
  }
}
