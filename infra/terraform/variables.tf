variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "aws_profile" {
  description = "인프라 생성 권한이 있는 AWS CLI 프로필 (kbap-prod-deployer 는 권한 부족)"
  type        = string
}
