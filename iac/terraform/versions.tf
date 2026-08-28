terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 원격 state (KB-390) — 버킷은 Terraform 밖에서 1회 생성(README "처음 세우기"). dev/prod 는 terraform workspace 로 분리한다:
  # 객체 키는 env:/<workspace>/ecs/terraform.tfstate. 잠금은 같은 버킷의 .tflock (use_lockfile, DynamoDB 불필요).
  # 백엔드 블록은 변수를 못 쓰므로 profile 을 고정한다 — provider 의 aws_profile 기본값과 같은 kbap-infra.
  backend "s3" {
    bucket       = "kbap-terraform-state"
    key          = "ecs/terraform.tfstate"
    region       = "ap-northeast-2"
    profile      = "kbap-infra"
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region  = var.region
  profile = var.aws_profile

  default_tags {
    tags = {
      Project   = "kbap"
      ManagedBy = "terraform"
    }
  }
}
