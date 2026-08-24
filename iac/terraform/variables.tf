variable "cluster_name" {
  description = "EKS 클러스터명"
  type        = string
  default     = "kbap-dev-eks"
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "aws_profile" {
  description = "인프라 생성 권한이 있는 AWS CLI 프로필 (kbap-prod-deployer는 권한 부족)"
  type        = string
  default     = "kbap-infra"
}

variable "vpc_name" {
  description = "재사용 VPC 태그 Name"
  type        = string
}

variable "admin_cidr" {
  description = "EKS API 엔드포인트 접근 허용 CIDR"
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes 버전"
  type        = string
  default     = "1.36"
}

variable "eks_public_subnet_a_cidr" {
  description = "EKS 퍼블릭 서브넷 A CIDR"
  type        = string
}

variable "eks_public_subnet_b_cidr" {
  description = "EKS 퍼블릭 서브넷 B CIDR"
  type        = string
}

variable "api_node_instance_types" {
  description = "API 노드 인스턴스 타입 목록"
  type        = list(string)
  default     = ["t3.medium"]
}

variable "ops_node_instance_types" {
  description = "Ops 노드(배치/모니터링) 인스턴스 타입 목록"
  type        = list(string)
  default     = ["t3.medium"]
}

variable "api_node_capacity_type" {
  description = "API 노드 capacity type: SPOT 또는 ON_DEMAND"
  type        = string
  default     = "SPOT"
}

variable "ops_node_capacity_type" {
  description = "Ops 노드 capacity type: SPOT 또는 ON_DEMAND"
  type        = string
  default     = "SPOT"
}

variable "node_disk_size" {
  description = "노드 루트 볼륨 크기(GB)"
  type        = number
  default     = 30
}

variable "cloudwatch_log_retention_days" {
  description = "EKS 컨트롤 플레인과 애플리케이션 로그 보관 일수"
  type        = number
  default     = 7
}

variable "api_service_account_name" {
  description = "API workload와 매핑할 Kubernetes ServiceAccount 이름"
  type        = string
  default     = "kbap-api"
}

variable "api_service_account_namespace" {
  description = "API ServiceAccount가 배포되는 네임스페이스"
  type        = string
  default     = "kbap-dev"
}

variable "api_s3_bucket_name" {
  description = "API가 presign/upload에 사용하는 S3 버킷"
  type        = string
  default     = "kbap-assets-kr"
}

variable "api_s3_key_prefix" {
  description = "API 권한을 허용할 S3 key 접두사(예: dev, prod)"
  type        = string
  default     = "dev"
}

variable "addon_versions" {
  description = "EKS 애드온 버전"
  type = object({
    vpc_cni                         = string
    coredns                         = string
    kube_proxy                      = string
    eks_pod_identity_agent          = string
    aws_ebs_csi_driver              = string
    amazon_cloudwatch_observability = string
  })
}

variable "rds_security_group_id" {
  description = "기존 RDS 인스턴스에 연결 허용할 소스 SG로 사용할 EKS node SG"
  type        = string
}

variable "redis_security_group_id" {
  description = "기존 Redis에 EKS node SG의 6379 연결을 허용할 대상 SG"
  type        = string
  default     = null
  nullable    = true
}

variable "enable_aws_load_balancer_controller" {
  description = "AWS Load Balancer Controller용 IAM 역할과 Pod Identity association 생성 여부"
  type        = bool
  default     = false
}

variable "api_alb_name" {
  description = "EKS API 앞에서 재사용할 기존 ALB 이름"
  type        = string
  default     = null
  nullable    = true
}

variable "api_public_hostname" {
  description = "EKS API로 라우팅할 공개 호스트명"
  type        = string
  default     = null
  nullable    = true
}

variable "route53_zone_name" {
  description = "API 별칭 레코드를 생성할 Route53 public hosted zone"
  type        = string
  default     = "kbap.site."
}

variable "api_alb_listener_rule_priority" {
  description = "기존 ALB HTTPS listener에 추가할 host rule priority"
  type        = number
  default     = 30
}
