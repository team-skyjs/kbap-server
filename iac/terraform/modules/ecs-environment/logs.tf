resource "aws_cloudwatch_log_group" "api" {
  name              = "/kbap/${var.env}/api"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

resource "aws_cloudwatch_log_group" "batch" {
  name              = "/kbap/${var.env}/batch"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

locals {
  dashboard_widgets = [
    {
      type = "metric", x = 0, y = 0, width = 12, height = 6
      properties = {
        title  = "ALB 요청 수 / 5xx"
        region = var.region
        stat   = "Sum"
        period = 60
        metrics = [
          ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", aws_lb.this.arn_suffix],
          [".", "HTTPCode_Target_5XX_Count", ".", ".", { color = "#d62728" }],
          [".", "HTTPCode_ELB_5XX_Count", ".", ".", { color = "#ff9896" }],
        ]
      }
    },
    {
      type = "metric", x = 12, y = 0, width = 12, height = 6
      properties = {
        title  = "응답 시간 p50 / p95 (초)"
        region = var.region
        period = 60
        metrics = [
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.this.arn_suffix, { stat = "p50" }],
          ["...", { stat = "p95", color = "#ff7f0e" }],
        ]
      }
    },
    {
      type = "metric", x = 0, y = 6, width = 12, height = 6
      properties = {
        title  = "정상 타깃 수 — blue / green (카나리 진행 확인)"
        region = var.region
        stat   = "Average"
        period = 60
        metrics = [
          ["AWS/ApplicationELB", "HealthyHostCount", "TargetGroup", aws_lb_target_group.api["blue"].arn_suffix, "LoadBalancer", aws_lb.this.arn_suffix, { label = "blue", color = "#1f77b4" }],
          [".", ".", ".", aws_lb_target_group.api["green"].arn_suffix, ".", ".", { label = "green", color = "#2ca02c" }],
        ]
      }
    },
    {
      type = "metric", x = 12, y = 6, width = 12, height = 6
      properties = {
        title  = "ECS CPU / 메모리 사용률 (%)"
        region = var.region
        stat   = "Average"
        period = 60
        metrics = [
          ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.this.name, "ServiceName", "${local.name_prefix}-api"],
          [".", "MemoryUtilization", ".", ".", ".", "."],
          [".", "CPUUtilization", ".", ".", ".", "${local.name_prefix}-batch", { label = "batch CPU" }],
          [".", "MemoryUtilization", ".", ".", ".", ".", { label = "batch Memory" }],
        ]
      }
    },
    {
      type = "log", x = 0, y = 12, width = 24, height = 8
      properties = {
        title  = "api ERROR 로그 (최근)"
        region = var.region
        query  = "SOURCE '${aws_cloudwatch_log_group.api.name}' | fields @timestamp, @message | filter @message like /ERROR/ | sort @timestamp desc | limit 50"
        view   = "table"
      }
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = local.name_prefix
  dashboard_body = jsonencode({ widgets = local.dashboard_widgets })
}
