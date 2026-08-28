# 관측(KB-381): 호스트마다 Grafana Alloy 1개(ECS DAEMON) — 같은 호스트의 api·batch /actuator/prometheus 와 호스트 자원을
# 15초마다 읽어 홈서버 Prometheus 로 remote_write 한다. 앱·ALB·SG 는 건드리지 않는다.
# 태스크 정의·서비스 모두 Terraform 이 완전 소유한다(CI 가 손대지 않으므로 ignore_changes 없음) — 설정 변경 = 템플릿 수정 후 apply.
locals {
  alloy_container_name = "alloy"
  alloy_service_name   = "${local.name_prefix}-alloy"

  alloy_config = templatefile("${path.module}/alloy.config.alloy.tftpl", {
    env              = var.env
    remote_write_url = var.home_prometheus_remote_write_url
  })
}

resource "aws_cloudwatch_log_group" "alloy" {
  name              = "/kbap/${var.env}/alloy"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

resource "aws_ecs_task_definition" "alloy" {
  family                   = local.alloy_service_name
  requires_compatibilities = ["EC2"]
  # host 네트워크: 컨테이너 hostname = EC2 호스트명(host 라벨), bridge 의 앱 컨테이너 IP 에 직접 도달, unix exporter 가 호스트 NIC 를 본다
  network_mode       = "host"
  cpu                = 128
  memory             = 384
  execution_role_arn = aws_iam_role.task_execution.arn

  volume {
    name      = "docker_sock"
    host_path = "/var/run/docker.sock"
  }
  volume {
    name      = "proc"
    host_path = "/proc"
  }
  volume {
    name      = "sys"
    host_path = "/sys"
  }
  volume {
    name      = "root"
    host_path = "/"
  }

  container_definitions = jsonencode([
    {
      name      = local.alloy_container_name
      image     = var.alloy_image
      essential = true
      # 카나리 중 api 1536 × 2 와 공존해야 하므로 예약은 작게, 상한은 WAL 스파이크 대비
      memoryReservation = 128
      # docker.sock(root:docker 660) 을 읽어야 한다 — 이미지 기본 사용자(alloy)로는 불가
      user = "0"

      # 이미지 ENTRYPOINT(/bin/alloy) 를 sh 로 바꿔 env 의 설정 본문을 파일로 쓴 뒤 실행. UI 포트는 localhost 에만(host 네트워크)
      entryPoint = ["sh", "-c"]
      command    = ["printf '%s' \"$ALLOY_CONFIG\" > /etc/alloy/config.alloy && exec /bin/alloy run --server.http.listen-addr=127.0.0.1:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config.alloy"]

      environment = [
        { name = "ALLOY_CONFIG", value = local.alloy_config }
      ]

      secrets = [for name in var.alloy_secret_names : {
        name      = name
        valueFrom = local.secret_arns[name]
      }]

      mountPoints = [
        { sourceVolume = "docker_sock", containerPath = "/var/run/docker.sock", readOnly = false },
        { sourceVolume = "proc", containerPath = "/host/proc", readOnly = true },
        { sourceVolume = "sys", containerPath = "/host/sys", readOnly = true },
        { sourceVolume = "root", containerPath = "/host/root", readOnly = true },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.alloy.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "alloy"
        }
      }
    }
  ])

  tags = local.common_tags
}

# DAEMON: 클러스터의 모든 컨테이너 인스턴스(api·batch 풀)에 정확히 1개. 인스턴스가 늘면 자동 배치, desired_count 없음.
resource "aws_ecs_service" "alloy" {
  name                = local.alloy_service_name
  cluster             = aws_ecs_cluster.this.id
  task_definition     = aws_ecs_task_definition.alloy.arn
  launch_type         = "EC2"
  scheduling_strategy = "DAEMON"

  # 호스트당 1개라 교체 시 잠깐 비는 것을 허용(수집 공백 수십 초, 앱 무영향)
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  tags = local.common_tags

  depends_on = [aws_autoscaling_group.pool]
}
