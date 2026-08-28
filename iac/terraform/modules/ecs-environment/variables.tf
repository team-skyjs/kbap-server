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
  type    = string
  default = "t3.medium"
}

variable "api_instance_count" {
  description = "api 전용 컨테이너 인스턴스 수 (블루/그린 중 구·신 태스크가 공존하므로 인스턴스당 태스크 2개 여유 필요)"
  type        = number
  default     = 2
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
  default = 2
}

variable "api_task_cpu" {
  type    = number
  default = 512
}

variable "api_task_memory" {
  description = "태스크 하드 메모리(MiB). t3.medium 에서 블루/그린 중 인스턴스당 2개가 올라가야 하므로 1536 이하 유지"
  type        = number
  default     = 1536
}

variable "batch_task_cpu" {
  type    = number
  default = 512
}

variable "batch_task_memory" {
  type    = number
  default = 1536
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
  default     = ["DB_PASSWORD", "JWT_SECRET", "OPENAI_API_KEY", "GOOGLE_PLACES_API_KEY", "FIREBASE_CREDENTIALS_JSON"]
}

variable "batch_secret_names" {
  type    = list(string)
  default = ["DB_PASSWORD", "OPENAI_API_KEY"]
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

# --- 관측: Grafana Alloy DAEMON (KB-381) ---
variable "home_prometheus_remote_write_url" {
  description = "홈서버 Prometheus remote_write 수신 URL — Cloudflare Tunnel 공개 호스트 (예: https://prom-write.example.com/api/v1/write)"
  type        = string
}

variable "alloy_image" {
  description = "Grafana Alloy 이미지 (태그 고정 — latest 금지)"
  type        = string
  default     = "grafana/alloy:v1.19.2"
}

variable "alloy_secret_names" {
  description = "Alloy 태스크에 SSM SecureString 으로 주입할 환경변수 이름 — Cloudflare Access 서비스 토큰. 등록: aws ssm put-parameter --name /kbap/<env>/<NAME> --type SecureString"
  type        = list(string)
  default     = ["CF_ACCESS_CLIENT_ID", "CF_ACCESS_CLIENT_SECRET"]
}
