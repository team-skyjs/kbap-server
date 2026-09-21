resource "aws_ecs_cluster" "this" {
  name = "${local.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = local.common_tags
}

# 워크로드별 인스턴스 풀 — ECS 인스턴스 속성(workload)으로 태스크 배치를 가른다
locals {
  instance_pools = {
    api = {
      count = var.api_instance_count
      max   = var.api_instance_max_count
    }
    batch = {
      count = var.batch_instance_count
      max   = var.batch_instance_count
    }
  }
}

resource "aws_launch_template" "pool" {
  for_each = local.instance_pools

  name_prefix   = "${local.name_prefix}-${each.key}-"
  image_id      = data.aws_ssm_parameter.ecs_ami.value
  instance_type = var.instance_type

  iam_instance_profile {
    arn = aws_iam_instance_profile.instance.arn
  }

  # NAT 없는 VPC — ECR pull·SSM 접근을 위해 퍼블릭 IP 필요
  network_interfaces {
    associate_public_ip_address = true
    security_groups             = [aws_security_group.instance.id]
  }

  metadata_options {
    http_tokens = "required"
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = 30
      volume_type           = "gp3"
      delete_on_termination = true
    }
  }

  user_data = base64encode(<<-EOT
    #!/bin/bash
    cat >> /etc/ecs/ecs.config <<'CFG'
    ECS_CLUSTER=${aws_ecs_cluster.this.name}
    ECS_INSTANCE_ATTRIBUTES={"workload":"${each.key}"}
    ECS_ENABLE_CONTAINER_METADATA=true
    ECS_RESERVED_MEMORY=256
    CFG
  EOT
  )

  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.common_tags, { Name = "${local.name_prefix}-${each.key}", Workload = each.key })
  }

  tags = local.common_tags
}

resource "aws_autoscaling_group" "pool" {
  for_each = local.instance_pools

  name                = "${local.name_prefix}-${each.key}-asg"
  min_size            = each.value.count
  max_size            = each.value.max
  desired_capacity    = each.value.count
  vpc_zone_identifier = data.aws_subnets.public.ids

  # api 풀은 capacity provider 가 대수를 조정한다 — 태스크가 도는 인스턴스는 scale-in 에서 보호
  protect_from_scale_in = each.key == "api"

  launch_template {
    id      = aws_launch_template.pool[each.key].id
    version = "$Latest"
  }

  # 런치 템플릿(AMI 등) 변경 시 인스턴스를 순차 교체
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage       = each.key == "api" ? 50 : 0
      scale_in_protected_instances = "Refresh"
    }
  }

  tag {
    key                 = "Name"
    value               = "${local.name_prefix}-${each.key}"
    propagate_at_launch = true
  }

  tag {
    key                 = "AmazonECSManaged"
    value               = "true"
    propagate_at_launch = true
  }

  lifecycle {
    ignore_changes = [desired_capacity]
  }
}

# api 풀 capacity provider — 태스크를 놓을 자리가 없으면 인스턴스를 늘린다.
# target_capacity 50 = 인스턴스 절반만 채움 → 평시 인스턴스당 태스크 1개, 나머지 절반은 카나리 그린 자리.
# batch·alloy 는 launch type EC2 그대로(기본 전략 없음).
resource "aws_ecs_capacity_provider" "api" {
  name = "${local.name_prefix}-api"

  auto_scaling_group_provider {
    auto_scaling_group_arn         = aws_autoscaling_group.pool["api"].arn
    managed_termination_protection = "ENABLED"

    managed_scaling {
      status          = "ENABLED"
      target_capacity = 50
    }
  }

  tags = local.common_tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = [aws_ecs_capacity_provider.api.name]
}
