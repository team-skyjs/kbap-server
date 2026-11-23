locals {
  batch_container_name = "batch"
  batch_service_name   = "${local.name_prefix}-batch"

  batch_env = merge({
    SPRING_PROFILES_ACTIVE = var.spring_profile
    DB_URL                 = var.db_url
    DB_USERNAME            = var.db_username
    REDIS_HOST             = var.redis_host
    REDIS_PORT             = var.redis_port
    AWS_REGION             = var.region
    FOOD_CONTENT_QUEUE_URL = data.aws_sqs_queue.food_content.url
    JAVA_TOOL_OPTIONS      = "-Xmx700m" # api 와 같은 이유 — 태스크 단위 memory만 있어 MaxRAMPercentage는 호스트 기준으로 계산됨(prod 힙 상한 1,294 MB > 태스크 1,200) → 힙 고정(24h 힙 최대 462 MB, 2026-11-04)
    SENTRY_RELEASE         = element(split(":", var.batch_image), 1)
    OTLP_METRICS_ENABLED   = "true"
    IMAGE_PUBLIC_BASE_URL  = var.image_public_base_url
  }, local.vector_env, var.batch_extra_env)
}

resource "aws_ecs_task_definition" "batch" {
  family                   = local.batch_service_name
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  cpu                      = var.batch_task_cpu
  memory                   = var.batch_task_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.batch_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = local.batch_container_name
      image     = var.batch_image
      essential = true

      # 잡 트리거 HTTP — 배치 인스턴스 고정 포트 (클러스터 내부에서만 접근)
      portMappings = [
        { containerPort = 8080, hostPort = var.batch_http_port, protocol = "tcp" }
      ]

      environment = [for k, v in local.batch_env : { name = k, value = v }]

      secrets = [for name in var.batch_secret_names : {
        name      = name
        valueFrom = local.secret_arns[name]
      }]

      # 응답 불능(프로세스 생존·앱 먹통) 시 ECS 가 태스크를 교체한다 — actuator 는 KB-380 부터 포함
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
          awslogs-group         = aws_cloudwatch_log_group.batch.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "batch"
        }
      }
    }
  ])

  tags = local.common_tags

  lifecycle {
    ignore_changes = [container_definitions]
  }
}

# 배치는 단일 인스턴스·단일 태스크 — 카나리 대상이 아니라 롤링(교체) 배포
resource "aws_ecs_service" "batch" {
  name            = local.batch_service_name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.batch.arn
  desired_count   = var.batch_desired_count
  launch_type     = "EC2"

  # 잡 원격 트리거 — 운영자가 aws ecs execute-command 로 컨테이너 안에서 트리거 HTTP 를 호출한다(포트 개방 없음)
  enable_execute_command = true

  placement_constraints {
    type       = "memberOf"
    expression = "attribute:workload == batch"
  }

  # 고정 호스트 포트라 인스턴스 1대에 태스크 2개 공존 불가 → 구 태스크를 먼저 내린다
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  tags = local.common_tags

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_autoscaling_group.pool]
}
