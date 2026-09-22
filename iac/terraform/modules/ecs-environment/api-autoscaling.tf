# api 서비스 오토스케일링 — 태스크 수를 조정하고, 자리가 모자라면 capacity provider(cluster.tf)가 인스턴스를 늘린다.
# 지표는 서비스 평균 CPU(태스크 예약 512 유닛 대비 %). DB 커넥션(RDS max_connections 60, Hikari 풀 10 유지): 평시 최대 4×10 + batch 10 = 50. 3태스크 이상으로 늘어난 채 카나리 배포하면 블루+그린이 60 을 넘을 수 있다 — 배포는 스케일 인 뒤에.
# scale-out cooldown 은 카나리 그린 JVM 부팅 버스트(2026-09-07 prod 롤백 원인)가 연쇄 증설로 번지지 않게 길게 둔다.
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
    scale_out_cooldown = 300
    scale_in_cooldown  = 300
  }
}
