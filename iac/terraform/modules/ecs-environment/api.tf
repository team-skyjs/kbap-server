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
  name            = local.api_service_name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "EC2"

  deployment_controller {
    type = "CODE_DEPLOY"
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
    target_group_arn = aws_lb_target_group.api["blue"].arn
    container_name   = local.api_container_name
    container_port   = 8080
  }

  health_check_grace_period_seconds = 180

  tags = local.common_tags

  # CodeDeploy 가 태스크 정의·타깃그룹을 바꾸므로 Terraform 은 초기 상태만 소유한다
  lifecycle {
    ignore_changes = [task_definition, load_balancer, desired_count]
  }

  depends_on = [aws_lb_listener.https, aws_autoscaling_group.pool]
}
