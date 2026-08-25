resource "aws_codedeploy_app" "api" {
  name             = "${local.name_prefix}-api"
  compute_platform = "ECS"
  tags             = local.common_tags
}

# 카나리: canary_percentage 만큼 신버전으로 canary_interval_minutes 동안 → 이상 없으면 100%
resource "aws_codedeploy_deployment_config" "canary" {
  deployment_config_name = "${local.name_prefix}-canary-${var.canary_percentage}p-${var.canary_interval_minutes}m"
  compute_platform       = "ECS"

  traffic_routing_config {
    type = "TimeBasedCanary"

    time_based_canary {
      percentage = var.canary_percentage
      interval   = var.canary_interval_minutes
    }
  }
}

resource "aws_codedeploy_deployment_group" "api" {
  app_name               = aws_codedeploy_app.api.name
  deployment_group_name  = "${local.name_prefix}-api"
  service_role_arn       = aws_iam_role.codedeploy.arn
  deployment_config_name = aws_codedeploy_deployment_config.canary.id

  deployment_style {
    deployment_option = "WITH_TRAFFIC_CONTROL"
    deployment_type   = "BLUE_GREEN"
  }

  blue_green_deployment_config {
    deployment_ready_option {
      action_on_timeout = "CONTINUE_DEPLOYMENT"
    }

    # 100% 전환 뒤에도 구버전 태스크를 유지 — 이 시간 안의 롤백은 트래픽만 되돌리면 끝
    terminate_blue_instances_on_deployment_success {
      action                           = "TERMINATE"
      termination_wait_time_in_minutes = var.blue_termination_wait_minutes
    }
  }

  auto_rollback_configuration {
    enabled = true
    events  = ["DEPLOYMENT_FAILURE", "DEPLOYMENT_STOP_ON_ALARM"]
  }

  alarm_configuration {
    alarms  = [aws_cloudwatch_metric_alarm.api_5xx.alarm_name]
    enabled = true
  }

  ecs_service {
    cluster_name = aws_ecs_cluster.this.name
    service_name = aws_ecs_service.api.name
  }

  load_balancer_info {
    target_group_pair_info {
      prod_traffic_route {
        listener_arns = [aws_lb_listener.https.arn]
      }
      target_group {
        name = aws_lb_target_group.api["blue"].name
      }
      target_group {
        name = aws_lb_target_group.api["green"].name
      }
    }
  }

  tags = local.common_tags
}

# 카나리 중 5xx 가 튀면 자동 롤백 트리거
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${local.name_prefix}-api-5xx"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = 10
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = aws_lb.this.arn_suffix
  }

  tags = local.common_tags
}
