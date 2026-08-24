resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/kbap-${var.env}-api"
  retention_in_days = 30
}

resource "aws_ecs_task_definition" "api" {
  family                   = "kbap-${var.env}-api"
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  cpu                      = var.container_cpu
  memory                   = var.container_memory

  container_definitions = jsonencode([
    {
      name      = "kbap-${var.env}-api"
      image     = var.api_image
      essential = true

      portMappings = [
        {
          containerPort = 8080
          hostPort      = 0
          protocol      = "tcp"
          appProtocol   = "http"
        }
      ]

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = var.env },
        { name = "DB_URL", value = "jdbc:mysql://${aws_db_instance.mysql.address}:3306/kbap" },
        { name = "DB_USERNAME", value = var.db_username },
        { name = "DB_PASSWORD", value = "CHANGE_ME" },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "STORAGE_BUCKET", value = var.storage_bucket },
        { name = "CDN_BASE_URL", value = var.cdn_base_url },
        { name = "IMAGE_PUBLIC_BASE_URL", value = var.image_public_base_url },
        { name = "JWT_SECRET", value = "CHANGE_ME" },
        { name = "OPENAI_API_KEY", value = "CHANGE_ME" },
        { name = "VISION_API_KEY", value = "CHANGE_ME" },
        { name = "UPSTAGE_API_KEY", value = "CHANGE_ME" },
        { name = "GOOGLE_API_KEY", value = "CHANGE_ME" },
        { name = "FIREBASE_CREDENTIALS_JSON", value = "CHANGE_ME" },
        { name = "AWS_ACCESS_KEY_ID", value = "CHANGE_ME" },
        { name = "AWS_SECRET_ACCESS_KEY", value = "CHANGE_ME" },
        { name = "AWS_SESSION_TOKEN", value = "" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = "ap-northeast-2"
          awslogs-stream-prefix = "api"
        }
      }
    }
  ])

  lifecycle {
    ignore_changes = [container_definitions]
  }
}

resource "aws_ecs_service" "api" {
  name            = "kbap-${var.env}-api-service"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.service_desired

  capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.asg.name
    weight            = 1
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.blue.arn
    container_name   = "kbap-${var.env}-api"
    container_port   = 8080
  }

  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  lifecycle {
    ignore_changes = [task_definition, load_balancer]
  }

  depends_on = [aws_lb_listener.http]
}
