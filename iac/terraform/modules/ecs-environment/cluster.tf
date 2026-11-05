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

locals {
  api_spot_mixed = var.spot_instances && length(var.api_spot_instance_types) > 0
}

resource "aws_launch_template" "pool" {
  for_each = local.instance_pools

  name_prefix   = "${local.name_prefix}-${each.key}-"
  image_id      = data.aws_ssm_parameter.ecs_ami.value
  instance_type = each.key == "batch" ? coalesce(var.batch_instance_type, var.instance_type) : var.instance_type

  # api 풀 Spot 은 ASG 의 mixed_instances_policy 가 시장을 정한다(템플릿에 시장 옵션이 있으면 혼합 정책을 못 쓴다)
  dynamic "instance_market_options" {
    for_each = var.spot_instances && !(local.api_spot_mixed && each.key == "api") ? [1] : []
    content {
      market_type = "spot"
    }
  }

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
    ECS_ENABLE_SPOT_INSTANCE_DRAINING=true
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

  # 버전을 숫자로 참조해야 템플릿 변경이 ASG 변경으로 잡혀 instance refresh 가 걸린다 — "$Latest" 는 provider 가 변경을 못 본다
  dynamic "launch_template" {
    for_each = local.api_spot_mixed && each.key == "api" ? [] : [1]
    content {
      id      = aws_launch_template.pool[each.key].id
      version = aws_launch_template.pool[each.key].latest_version
    }
  }

  # dev api 풀 Spot — 한 타입·한 AZ 용량 부족으로 재기동이 막히지 않게 여러 arm64 타입에서 고른다(2026-11-05 dev 503).
  # 온디맨드 0, price-capacity-optimized, 회수 예고 전에 대체 인스턴스를 먼저 띄운다(capacity_rebalance).
  dynamic "mixed_instances_policy" {
    for_each = local.api_spot_mixed && each.key == "api" ? [1] : []
    content {
      launch_template {
        launch_template_specification {
          launch_template_id = aws_launch_template.pool[each.key].id
          version            = aws_launch_template.pool[each.key].latest_version
        }

        dynamic "override" {
          for_each = var.api_spot_instance_types
          content {
            instance_type = override.value
          }
        }
      }

      instances_distribution {
        on_demand_base_capacity                  = 0
        on_demand_percentage_above_base_capacity = 0
        spot_allocation_strategy                 = "price-capacity-optimized"
      }
    }
  }

  capacity_rebalance = local.api_spot_mixed && each.key == "api"

  # 런치 템플릿(AMI·인스턴스 타입·Spot) 변경 시 인스턴스를 순차 교체. 추천 AMI 가 갱신된 뒤의 apply 도 롤링을 유발한다(plan 의 launch_template.version 변경)
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
# target_capacity 100 = 빈 인스턴스를 두지 않는다. ECS 는 태스크가 하나라도 있는 인스턴스를 "사용 중"으로 세므로
# 50 은 "인스턴스마다 절반"이 아니라 "빈 인스턴스를 같은 수만큼 더"다(dev 에서 2 → 4대로 확인).
# 평시 인스턴스당 태스크 1개(spread). 롤링(min 50/max 100)은 자리를 더 쓰지 않으므로 태스크 스케일 아웃으로 자리가 모자랄 때만 인스턴스가 는다.
# batch 는 launch type EC2 그대로(기본 전략 없음).
resource "aws_ecs_capacity_provider" "api" {
  name = "${local.name_prefix}-api"

  auto_scaling_group_provider {
    auto_scaling_group_arn         = aws_autoscaling_group.pool["api"].arn
    managed_termination_protection = "ENABLED"

    managed_scaling {
      status          = "ENABLED"
      target_capacity = 100
    }
  }

  tags = local.common_tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = [aws_ecs_capacity_provider.api.name]
}
