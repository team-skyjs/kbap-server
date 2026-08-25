variable "env" {
  description = "dev | prod — 리소스 접두어·SSM 경로·Spring 프로필 기본값"
  type        = string
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "aws_profile" {
  description = "인프라 생성 권한이 있는 AWS CLI 프로필 (kbap-prod-deployer 는 권한 부족)"
  type        = string
  default     = "kbap-infra"
}

variable "vpc_name" {
  type = string
}

variable "subdomain" {
  description = "kbap.site 아래 서브도메인 — dev-ecs / prod-ecs"
  type        = string
}

variable "hosted_zone_name" {
  type    = string
  default = "kbap.site"
}

variable "instance_type" {
  type    = string
  default = "t3.medium"
}

variable "api_instance_count" {
  type    = number
  default = 2
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

variable "api_image" {
  type = string
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
  type    = string
  default = "root"
}

variable "redis_host" {
  type = string
}

variable "rds_security_group_id" {
  type = string
}

variable "redis_security_group_id" {
  type = string
}

variable "storage_bucket" {
  type    = string
  default = "kbap-assets-kr"
}

variable "storage_key_prefix" {
  type = string
}

variable "cdn_base_url" {
  type    = string
  default = "https://d29c1cr2ng7w0.cloudfront.net"
}

variable "image_public_base_url" {
  type    = string
  default = "https://d29c1cr2ng7w0.cloudfront.net/"
}

variable "food_content_queue_name" {
  type    = string
  default = "kbap-generate-content-queue"
}

variable "log_retention_days" {
  type    = number
  default = 7
}

variable "canary_percentage" {
  type    = number
  default = 20
}

variable "canary_interval_minutes" {
  type    = number
  default = 15
}

variable "blue_termination_wait_minutes" {
  type    = number
  default = 15
}

variable "admin_cidr" {
  type = string
}

variable "bastion_instance_type" {
  type    = string
  default = "t3.nano"
}

variable "bastion_key_name" {
  type = string
}
