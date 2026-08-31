mock_provider "aws" {
  mock_data "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = jsonencode({
        Version   = "2012-10-17"
        Statement = []
      })
    }
  }

  mock_data "aws_subnets" {
    defaults = {
      ids = ["subnet-00000000000000001", "subnet-00000000000000002"]
    }
  }
}

variables {
  vpc_name                         = "test-vpc"
  subdomain                        = "test-ecs"
  api_image                        = "example.invalid/kbap/api:test"
  batch_image                      = "example.invalid/kbap/batch:test"
  spring_profile                   = "dev"
  db_url                           = "jdbc:mysql://db.invalid:3306/kbap"
  db_username                      = "test"
  redis_host                       = "redis.invalid"
  rds_security_group_id            = "sg-00000000000000001"
  redis_security_group_id          = "sg-00000000000000002"
  storage_bucket                   = "test-storage-bucket"
  storage_key_prefix               = "test"
  cdn_base_url                     = "https://cdn.invalid"
  image_public_base_url            = "https://cdn.invalid/"
  food_content_queue_name          = "test-food-content"
  admin_cidr                       = "192.0.2.1/32"
  bastion_key_name                 = "test-key"
  home_prometheus_remote_write_url = "https://prometheus.invalid/api/v1/write"
}

run "dev_performance_profiling_is_valid" {
  command = plan

  module {
    source = "./modules/ecs-environment"
  }

  variables {
    env                         = "dev"
    api_execute_command_enabled = true
  }

  assert {
    condition     = aws_ecs_service.api.enable_execute_command
    error_message = "dev profiling must enable ECS Exec on the API service"
  }

  assert {
    condition     = length(aws_s3_bucket.performance_artifacts) == 1
    error_message = "dev profiling must create exactly one performance artifact bucket"
  }

  assert {
    condition     = aws_s3_bucket.performance_artifacts[0].bucket == "kbap-dev-ecs-performance-artifacts"
    error_message = "dev profiling must use the dedicated performance artifact bucket name"
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.performance_artifacts[0].block_public_acls,
      aws_s3_bucket_public_access_block.performance_artifacts[0].block_public_policy,
      aws_s3_bucket_public_access_block.performance_artifacts[0].ignore_public_acls,
      aws_s3_bucket_public_access_block.performance_artifacts[0].restrict_public_buckets,
    ])
    error_message = "the performance artifact bucket must block every form of public access"
  }

  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.performance_artifacts[0].rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "the performance artifact bucket must use AES256 encryption"
  }

  assert {
    condition     = one(one(aws_s3_bucket_lifecycle_configuration.performance_artifacts[0].rule).expiration).days == 7
    error_message = "the performance artifact bucket must expire objects after the configured retention"
  }
}

run "prod_performance_profiling_is_rejected" {
  command = plan

  module {
    source = "./modules/ecs-environment"
  }

  variables {
    env                         = "prod"
    spring_profile              = "prod"
    api_execute_command_enabled = true
  }

  assert {
    condition     = !local.performance_profiling_enabled
    error_message = "prod must disable the effective profiling gate even when profiling is requested"
  }

  assert {
    condition     = length(aws_s3_bucket.performance_artifacts) == 0
    error_message = "prod must not create performance artifact resources even when profiling is requested"
  }

  expect_failures = [aws_ecs_service.api]
}
