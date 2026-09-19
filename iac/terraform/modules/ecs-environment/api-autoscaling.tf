# api 서비스 오토스케일링 — 태스크 수만 2~4 로 조정한다(EC2 ASG 는 고정 2대, 인스턴스당 2 태스크는 메모리 예약 1536MiB 가 자연 상한).
# 지표는 서비스 평균 CPU(태스크 예약 512 유닛 대비 %). 태스크 4개 평시 DB 커넥션 = 4×10 + batch 10 = 50.
resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.api_desired_count
  max_capacity       = var.api_max_count
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${local.name_prefix}-api-cpu"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = var.api_cpu_target_percent
    scale_out_cooldown = 60
    scale_in_cooldown  = 300
  }
}
