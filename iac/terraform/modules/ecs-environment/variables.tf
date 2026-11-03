variable "env" {
  description = "환경 이름 — 리소스 이름 접두어와 SSM 파라미터 경로(/kbap/<env>/...)에 쓰인다"
  type        = string
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "vpc_name" {
  description = "재사용할 기존 VPC 의 Name 태그"
  type        = string
}

variable "public_subnet_name_pattern" {
  description = "ALB·컨테이너 인스턴스를 놓을 퍼블릭 서브넷 Name 태그 패턴 (EKS 전용 서브넷은 제외되도록 기본값 유지)"
  type        = string
  default     = "*subnet-public*"
}

variable "hosted_zone_name" {
  type    = string
  default = "kbap.site"
}

variable "subdomain" {
  description = "hosted_zone_name 아래 서브도메인 (예: dev-ecs → dev-ecs.kbap.site)"
  type        = string
}

variable "certificate_domain" {
  description = "ACM 에서 조회할 인증서 도메인 (와일드카드 인증서의 대표 도메인)"
  type        = string
  default     = "kbap.site"
}

variable "instance_type" {
  description = "컨테이너 인스턴스 타입 — arm64(Graviton) 계열만. AMI 가 arm64 로 고정돼 있어 x86 타입을 넣으면 부팅하지 않는다"
  type        = string
  default     = "t4g.medium"
}

variable "batch_instance_type" {
  description = "batch 풀 인스턴스 타입. null 이면 instance_type 과 같다. api 는 기동만으로 1.3 GiB 를 넘어 t4g.small(2 GiB, 등록 1590) 에 못 들어가지만 batch 는 들어가므로 풀마다 다르게 둔다(2026-11-03 실측)"
  type        = string
  default     = "t4g.small"
}

variable "spot_instances" {
  description = "컨테이너 인스턴스를 Spot 으로 띄운다(dev 전용). 회수 2분 전 ECS 가 인스턴스를 드레인한다(ECS_ENABLE_SPOT_INSTANCE_DRAINING)"
  type        = bool
  default     = false
}

variable "api_instance_count" {
  description = "api 전용 컨테이너 인스턴스 수. 2026-11-03 부터 1대 — 카나리 중 구·신 태스크 2개가 한 인스턴스에 공존한다(api_task_memory × 2 ≤ 등록 메모리)"
  type        = number
  default     = 1
}

variable "api_instance_max_count" {
  description = "api 인스턴스 풀 상한. 1 이면 capacity provider 가 대수를 늘리지 못해 비용이 고정된다(2026-11-03 결정)"
  type        = number
  default     = 1
}

variable "batch_instance_count" {
  type    = number
  default = 1
}

variable "batch_desired_count" {
  description = "batch 태스크 수 — 0 이면 배치를 띄우지 않는다(인스턴스도 0 으로 맞출 것)"
  type        = number
  default     = 1
}

variable "api_desired_count" {
  type    = number
  default = 1
}

variable "api_task_cpu" {
  description = "태스크 CPU 예약(유닛). null = 상한 없음 — 인스턴스 CPU 전부를 쓴다"
  type        = number
  default     = null
}

variable "api_task_memory" {
  description = "태스크 하드 메모리(MiB). 카나리 중 한 인스턴스에 2개가 공존하므로 ECS 등록 메모리(이미 ECS_RESERVED_MEMORY 256 을 뺀 값) ÷ 2 이하 — t4g.medium 3,579 ÷ 2 = 1,789 → 1,750"
  type        = number
  default     = 1750
}

variable "batch_task_cpu" {
  description = "api_task_cpu 와 같다"
  type        = number
  default     = null
}

variable "batch_task_memory" {
  description = "api_task_memory 와 같다"
  type        = number
  default     = 1200
}

variable "api_image" {
  description = "초기 api 이미지 (ECR URI:태그). 이후 리비전은 배포 스크립트/CI 가 소유한다"
  type        = string
}

variable "batch_image" {
  type = string
}

variable "spring_profile" {
  type = string
}

variable "db_url" {
  type = string
}

variable "db_username" {
  type = string
}

variable "redis_host" {
  type = string
}

variable "redis_port" {
  type    = string
  default = "6379"
}

variable "rds_security_group_id" {
  description = "기존 RDS 의 보안그룹 — 컨테이너 인스턴스 SG 를 3306 인바운드로 추가한다"
  type        = string
}

variable "redis_security_group_id" {
  description = "기존 ElastiCache 의 보안그룹 — 컨테이너 인스턴스 SG 를 6379 인바운드로 추가한다"
  type        = string
}

variable "storage_bucket" {
  type = string
}

variable "storage_key_prefix" {
  type = string
}

variable "cdn_base_url" {
  type = string
}

variable "image_public_base_url" {
  type = string
}

variable "food_content_queue_name" {
  description = "배치가 발행하는 SQS 큐 이름 (URL·ARN 은 data 로 조회)"
  type        = string
}

variable "api_secret_names" {
  description = "api 태스크에 SSM SecureString 으로 주입할 환경변수 이름 목록"
  type        = list(string)
  default     = ["DB_PASSWORD", "JWT_SECRET", "OPENAI_API_KEY", "GOOGLE_PLACES_API_KEY", "FIREBASE_CREDENTIALS_JSON", "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY", "API_SENTRY_DSN", "ANTHROPIC_API_KEY", "OTLP_METRICS_URL", "CF_ACCESS_CLIENT_ID", "CF_ACCESS_CLIENT_SECRET"]
  nullable    = false
}

variable "batch_secret_names" {
  type     = list(string)
  default  = ["DB_PASSWORD", "OPENAI_API_KEY", "BATCH_SENTRY_DSN", "SLACK_STATS_WEBHOOK_URL", "OTLP_METRICS_URL", "CF_ACCESS_CLIENT_ID", "CF_ACCESS_CLIENT_SECRET"]
  nullable = false
}

variable "api_extra_env" {
  description = "api 컨테이너에 추가로 넣을 평문 환경변수"
  type        = map(string)
  default     = {}
}

variable "batch_extra_env" {
  type    = map(string)
  default = {}
}

variable "log_retention_days" {
  type    = number
  default = 7
}

variable "api_execute_command_enabled" {
  type    = bool
  default = false
}

variable "performance_artifact_retention_days" {
  type    = number
  default = 7
}

variable "batch_http_port" {
  description = "배치 앱의 잡 트리거 HTTP 포트 (배치 인스턴스에 고정 매핑, 클러스터 내부에서만 접근)"
  type        = number
  default     = 8080
}

variable "admin_cidr" {
  description = "bastion SSH 접근 허용 CIDR (관리자 공인 IP/32)"
  type        = string
}

variable "bastion_instance_type" {
  type    = string
  default = "t3.nano"
}

variable "bastion_key_name" {
  description = "bastion EC2 키페어 이름 (SSH 터널용)"
  type        = string
}


variable "canary_percentage" {
  description = "카나리 단계에서 신버전으로 보낼 트래픽 비율(%)"
  type        = number
  default     = 20
}

variable "canary_interval_minutes" {
  description = "카나리 비율을 유지한 뒤 100% 로 전환하기까지의 시간(분)"
  type        = number
  default     = 15
}

variable "blue_termination_wait_minutes" {
  description = "100% 전환 후 구버전 태스크를 유지하는 시간(분) — 이 안에는 즉시 롤백 가능"
  type        = number
  default     = 15
}

variable "blocked_path_patterns" {
  description = "공개 진입점(HTTPS 리스너)에서 404 로 막을 ALB path-pattern 목록(최대 5개). 허용 목록이 아니라 거부 규칙(KB-390 당시 CodeDeploy 전환과의 충돌 회피 — 롤링 전환 뒤에도 유지). `*` 는 `/` 를 포함해 //actuator/… 같은 변형도 잡는다."
  type        = list(string)
  default     = ["*actuator*"]
}

variable "vector_enabled" {
  description = "S3 Vectors 벡터 검색(api, KB-319)·적재(batch foodVectorSyncJob, KB-328) 앱 스위치. 버킷·인덱스·IAM 은 항상 만들어지고(서버리스, 상시 비용 0), 켜면 VECTOR_ENABLED·EMBEDDING_ENABLED=true 와 VECTOR_BUCKET·VECTOR_INDEX 를 api·batch env 에 주입한다. 시크릿 없음 — 태스크 롤로 인증."
  type        = bool
  default     = false
}

variable "api_max_count" {
  description = "api 태스크 수 상한(서비스 오토스케일링). 인스턴스 1대·태스크 1,750 에선 2개가 되면 카나리 그린 태스크셋 자리가 없어 배포가 막히므로 1 로 고정(2026-11-03)"
  type        = number
  default     = 1
}

variable "api_cpu_target_percent" {
  description = "api 서비스 오토스케일링 목표 CPU (%). 태스크 예약 CPU 대비"
  type        = number
  default     = 80
}

variable "api_slow_start_seconds" {
  description = "api 대상 그룹 slow start(초). 새 타깃의 트래픽 비중을 이 시간 동안 선형 상승. 0 이면 끔"
  type        = number
  default     = 60
}
