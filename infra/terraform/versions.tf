terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # state 백엔드 — 버킷 생성 후 주석 해제하고 `terraform init -migrate-state`
  # backend "s3" {
  #   bucket       = "kbap-terraform-state"
  #   key          = "env/dev/terraform.tfstate"
  #   region       = "ap-northeast-2"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile

  default_tags {
    tags = {
      Project   = "kbap"
      ManagedBy = "terraform"
    }
  }
}
