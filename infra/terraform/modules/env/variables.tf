variable "env" {
  type = string
}

variable "vpc_name" {
  type = string
}

variable "instance_type" {
  type = string
}

variable "asg_min_size" {
  type = number
}

variable "asg_max_size" {
  type = number
}

variable "service_desired" {
  type = number
}

variable "container_cpu" {
  type = number
}

variable "container_memory" {
  type = number
}

variable "db_instance_class" {
  type = string
}

variable "redis_node_type" {
  type = string
}

variable "api_image" {
  description = "ECR 이미지 (git SHA 태그) — 배포 파이프라인이 이후 리비전을 소유한다"
  type        = string
}

variable "certificate_arn" {
  description = "443 리스너용 ACM 인증서 — null 이면 80 만 연다"
  type        = string
  default     = null
}

variable "db_username" {
  type = string
}

variable "storage_bucket" {
  type = string
}

variable "cdn_base_url" {
  type = string
}

variable "image_public_base_url" {
  type = string
}
