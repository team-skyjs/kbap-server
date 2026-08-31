# Contract: Alloy 설정 템플릿 · terraform 인터페이스 · 홈서버 수신 (KB-381)

## 1. terraform 모듈 입력 (`modules/ecs-environment`)

| 변수 | 타입 | 예 | 비고 |
|---|---|---|---|
| `home_prometheus_remote_write_url` | string | `https://prom-write.example.com/api/v1/write` | 사용자 제공 |
| `alloy_image` | string | `grafana/alloy:v1.x.y` | `latest` 금지 |
| `alloy_secret_names` | list(string) | `["CF_ACCESS_CLIENT_ID","CF_ACCESS_CLIENT_SECRET"]` | `secrets.tf` 의 `secret_names` 에 합류 |

SSM 등록(사용자): `aws ssm put-parameter --name /kbap/<env>/CF_ACCESS_CLIENT_ID --type SecureString --value '...' --overwrite` (SECRET 동일).

## 2. ECS 리소스 (`alloy.tf`)

- `aws_cloudwatch_log_group.alloy` — `/kbap/<env>/alloy`, `log_retention_days`
- `aws_ecs_task_definition.alloy` — family `<prefix>-alloy`, `network_mode = "host"`, `requires_compatibilities = ["EC2"]`, `cpu 128`, `memory 384`, execution role = 기존 `task_execution`, task role 없음
  - volumes(host path): `docker_sock` `/var/run/docker.sock`, `proc` `/proc`, `sys` `/sys`, `root` `/`
  - container `alloy`: image `var.alloy_image`, `memoryReservation 128`, `command` = 아래 3, `environment` `ALLOY_CONFIG` = `templatefile(...)`, `secrets` 2개, `mountPoints`(sock rw / proc·sys·root ro), awslogs
- `aws_ecs_service.alloy` — `scheduling_strategy = "DAEMON"`, `launch_type = "EC2"`, `deployment_minimum_healthy_percent = 0`, `deployment_maximum_percent = 100`. `ignore_changes` 없음.

## 3. 컨테이너 command

```
sh -c 'printf "%s" "$ALLOY_CONFIG" > /etc/alloy/config.alloy && exec alloy run --server.http.listen-addr=127.0.0.1:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config.alloy'
```

## 4. `alloy.config.alloy.tftpl` (치환: `${env}`, `${remote_write_url}`)

```alloy
discovery.docker "ecs" {
  host             = "unix:///var/run/docker.sock"
  refresh_interval = "15s"
}

discovery.relabel "apps" {
  targets = discovery.docker.ecs.targets
  rule {
    source_labels = ["__meta_docker_container_label_com_amazonaws_ecs_container_name"]
    regex         = "api|batch"
    action        = "keep"
  }
  rule {
    source_labels = ["__meta_docker_port_private"]
    regex         = "8080"
    action        = "keep"
  }
  rule {
    target_label = "__metrics_path__"
    replacement  = "/actuator/prometheus"
  }
  rule {
    source_labels = ["__meta_docker_container_label_com_amazonaws_ecs_container_name",
                     "__meta_docker_container_label_com_amazonaws_ecs_task_arn"]
    separator     = ";"
    regex         = "(.+);.*/([0-9a-f]{6})$"
    target_label  = "instance"
    replacement   = "${env}-$${1}-$${2}"
  }
  rule {
    source_labels = ["__meta_docker_container_label_com_amazonaws_ecs_task_definition_version"]
    target_label  = "version"
  }
}

prometheus.scrape "ecs_apps" {
  targets         = discovery.relabel.apps.output
  forward_to      = [prometheus.remote_write.home.receiver]
  scrape_interval = "15s"
  scrape_timeout  = "10s"
}

prometheus.exporter.unix "host" {
  procfs_path = "/host/proc"
  sysfs_path  = "/host/sys"
  rootfs_path = "/host/root"
  filesystem {
    mount_points_exclude = "^/(dev|proc|sys|run|var/lib/docker/.+)($|/)"
  }
}

prometheus.scrape "host" {
  targets         = prometheus.exporter.unix.host.targets
  forward_to      = [prometheus.remote_write.home.receiver]
  scrape_interval = "15s"
}

prometheus.remote_write "home" {
  endpoint {
    url = "${remote_write_url}"
    headers = {
      "CF-Access-Client-Id"     = sys.env("CF_ACCESS_CLIENT_ID"),
      "CF-Access-Client-Secret" = sys.env("CF_ACCESS_CLIENT_SECRET"),
    }
  }
  external_labels = {
    env  = "${env}",
    host = constants.hostname,
  }
}
```

(terraform templatefile 에서 Alloy 의 `$1` 참조는 `$${1}` 로 이스케이프한다.)

## 5. 홈서버 수신 계약

| 항목 | 값 |
|---|---|
| 공개 호스트 | `prom-write.<도메인>` (Cloudflare Tunnel ingress → `http://prometheus:9090`) |
| 경로 | `POST /api/v1/write` (Prometheus remote write v1, snappy+protobuf — Alloy 기본) |
| 인증 | Cloudflare Access 서비스 토큰(헤더 2개). 없거나 틀리면 403 |
| Prometheus 플래그 | `--web.enable-remote-write-receiver` |
| 노출 금지 | `/graph`·`/api/v1/query` 등 UI/조회 경로는 공개 호스트에 넣지 않는다 |

## 6. Grafana 에서의 기대 조회 (검증 기준)

```promql
up{env="dev", job="prometheus.scrape.ecs_apps"}                  # 앱 태스크 수만큼 1 (instance=dev-api-xxxxxx / dev-batch-xxxxxx, version=<리비전>)
count by (host) (node_memory_MemAvailable_bytes{env="dev"})       # 인스턴스 수
sum by (version) (rate(http_server_requests_seconds_count{env="dev",application="kbap-api"}[5m]))
```
