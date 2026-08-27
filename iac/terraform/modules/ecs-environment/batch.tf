locals {
  batch_container_name = "batch"
  batch_service_name   = "${local.name_prefix}-batch"

  batch_env = merge({
    SPRING_PROFILES_ACTIVE   = var.spring_profile
    SPRING_BATCH_JOB_ENABLED = "true"
    DB_URL                   = var.db_url
    DB_USERNAME              = var.db_username
    REDIS_HOST               = var.redis_host
    REDIS_PORT               = var.redis_port
    AWS_REGION               = var.region
    FOOD_CONTENT_QUEUE_URL   = data.aws_sqs_queue.food_content.url
    JAVA_TOOL_OPTIONS        = "-XX:MaxRAMPercentage=70"
  }, var.batch_extra_env)
}

resource "aws_ecs_task_definition" "batch" {
  family                   = local.batch_service_name
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  cpu                      = var.batch_task_cpu
  memory                   = var.batch_task_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.batch_task.arn

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
