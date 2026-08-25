terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 원격 state — 버킷 생성 후 주석 해제하고 `terraform init -migrate-state`.
  # dev/prod 는 workspace 가 아니라 key 로 분리한다 (-backend-config="key=ecs/<env>/terraform.tfstate").
  # backend "s3" {
  #   bucket       = "kbap-terraform-state"
  #   region       = "ap-northeast-2"
  #   use_lockfile = true
  # }
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
