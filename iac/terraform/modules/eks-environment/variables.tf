variable "cluster_name" {
  type = string
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "profile" {
  type = string
}

variable "vpc_name" {
  type = string
}

variable "admin_cidr" {
  type        = string
  description = "Kubernetes API 접근 허용 CIDR"
}

variable "kubernetes_version" {
  type    = string
  default = "1.36"
}

variable "eks_public_subnet_a_cidr" {
  type = string
}

variable "eks_public_subnet_b_cidr" {
  type = string
}

variable "addon_versions" {
  type = object({
    vpc_cni                = string
    coredns                = string
    kube_proxy             = string
    eks_pod_identity_agent = string
    aws_ebs_csi_driver     = string
  })
}

variable "api_node_instance_types" {
  type = list(string)
}

variable "ops_node_instance_types" {
  type = list(string)
}

variable "api_node_capacity_type" {
  type        = string
  default     = "SPOT"
  description = "SPOT or ON_DEMAND"
}

variable "ops_node_capacity_type" {
  type        = string
  default     = "SPOT"
  description = "SPOT or ON_DEMAND"
}

variable "node_disk_size" {
  type    = number
  default = 30
}

variable "cloudwatch_log_retention_days" {
  type    = number
  default = 7
}
