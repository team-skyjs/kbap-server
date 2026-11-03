locals {
  api_container_name = "api"
  api_service_name   = "${local.name_prefix}-api"

  api_env = merge({
    SPRING_PROFILES_ACTIVE = var.spring_profile
    DB_URL                 = var.db_url
    DB_USERNAME            = var.db_username
    REDIS_HOST             = var.redis_host
    REDIS_PORT             = var.redis_port
    STORAGE_BUCKET         = var.storage_bucket
    STORAGE_KEY_PREFIX     = var.storage_key_prefix
    STORAGE_REGION         = var.region
    CDN_BASE_URL           = var.cdn_base_url
    IMAGE_PUBLIC_BASE_URL  = var.image_public_base_url
    JAVA_TOOL_OPTIONS      = "-XX:MaxRAMPercentage=70"
    SENTRY_RELEASE         = element(split(":", var.api_image), 1)
    OTLP_METRICS_ENABLED   = "true" # 앱이 /api/v1/otlp/v1/metrics 로 15초마다 직접 푸시(Alloy 대체, 2026-11-03). URL·Access 토큰은 secrets
  }, local.vector_env, var.api_extra_env)
}

resource "aws_ecs_task_definition" "api" {
  family                   = local.api_service_name
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  cpu                      = var.api_task_cpu
  memory                   = var.api_task_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = local.api_container_name
      image     = var.api_image
      essential = true

      portMappings = [
        { containerPort = 8080, hostPort = 0, protocol = "tcp", appProtocol = "http" }
      ]

      environment = [for k, v in local.api_env : { name = k, value = v }]

      secrets = [for name in var.api_secret_names : {
        name      = name
        valueFrom = local.secret_arns[name]
      }]

      healthCheck = {
        command     = ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 150
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "api"
        }
      }
    }
  ])

  tags = local.common_tags

  # 이후 리비전(이미지 태그 교체)은 배포 스크립트/CI 가 등록한다
  lifecycle {
    ignore_changes = [container_definitions]
  }
}

resource "aws_ecs_service" "api" {
  name                   = local.api_service_name
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.api.arn
  desired_count          = var.api_desired_count
  enable_execute_command = local.performance_profiling_enabled

  # 컨트롤러 전환(CODE_DEPLOY → ECS)은 제자리 변경이 안 돼 서비스가 재생성된다(잠깐 다운) — 2026-11-01 1회성
  deployment_controller {
    type = "ECS"
  }

  # 롤링 — 인스턴스당 태스크 1개라 신 태스크를 미리 올릴 자리가 없다. 한 대씩 구 태스크를 내리고(드레인 30초) 같은 자리에 신 태스크를 올린다.
  # 그 사이 나머지 인스턴스가 트래픽을 받는다. 기동 실패·헬스체크 탈락은 서킷브레이커가 이전 리비전으로 되돌린다.
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # 태스크 스케일 아웃(api-autoscaling.tf)으로 자리가 모자라면 capacity provider 가 인스턴스를 늘린다
  capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.api.name
    weight            = 1
    base              = 0
  }

  placement_constraints {
    type       = "memberOf"
    expression = "attribute:workload == api"
  }

  ordered_placement_strategy {
    type  = "spread"
    field = "instanceId"
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = local.api_container_name
    container_port   = 8080
  }

  health_check_grace_period_seconds = 180

  tags = local.common_tags

  # 태스크 정의 리비전은 CI 가 등록해 update-service 로 올리고, desired_count 는 오토스케일링이 움직인다 — Terraform 은 초기값만 소유한다.
  lifecycle {
    ignore_changes = [task_definition, desired_count]

    precondition {
      condition     = !var.api_execute_command_enabled || var.env == "dev"
      error_message = "performance profiling can only be enabled when env is dev"
    }
  }

  depends_on = [aws_lb_listener.https, aws_autoscaling_group.pool, aws_ecs_cluster_capacity_providers.this]
}
