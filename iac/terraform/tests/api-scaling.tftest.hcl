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
  vpc_name                = "test-vpc"
  subdomain               = "test-ecs"
  api_image               = "example.invalid/kbap/api:test"
  batch_image             = "example.invalid/kbap/batch:test"
  spring_profile          = "dev"
  db_url                  = "jdbc:mysql://db.invalid:3306/kbap"
  db_username             = "test"
  redis_host              = "redis.invalid"
  rds_security_group_id   = "sg-00000000000000001"
  redis_security_group_id = "sg-00000000000000002"
  storage_bucket          = "test-storage-bucket"
  storage_key_prefix      = "test"
  cdn_base_url            = "https://cdn.invalid"
  image_public_base_url   = "https://cdn.invalid/"
  food_content_queue_name = "test-food-content"
  admin_cidr              = "192.0.2.1/32"
  bastion_key_name        = "test-key"
}

run "api_scales_tasks_at_80_percent_and_instances_via_capacity_provider" {
  command = plan

  module {
    source = "./modules/ecs-environment"
  }

  variables {
    env = "dev"
    # 2026-11-03 기본값은 1대 고정(api_instance_max_count 1) — 풀 확장 규칙 자체는 입력을 주고 검증한다
    api_instance_count     = 2
    api_instance_max_count = 4
  }

  assert {
    condition     = one(aws_appautoscaling_policy.api_cpu.target_tracking_scaling_policy_configuration).target_value == 80
    error_message = "api service must scale out at 80% of the task CPU reservation"
  }

  assert {
    condition     = aws_autoscaling_group.pool["api"].max_size == 4 && aws_autoscaling_group.pool["api"].min_size == 2
    error_message = "api instance pool must be allowed to grow from api_instance_count to api_instance_max_count"
  }

  assert {
    condition     = aws_autoscaling_group.pool["batch"].max_size == aws_autoscaling_group.pool["batch"].min_size
    error_message = "batch instance pool must stay fixed"
  }

  assert {
    condition     = aws_autoscaling_group.pool["api"].protect_from_scale_in && !aws_autoscaling_group.pool["batch"].protect_from_scale_in
    error_message = "only the api pool needs scale-in protection for managed termination protection"
  }

  assert {
    condition     = one(one(aws_ecs_capacity_provider.api.auto_scaling_group_provider).managed_scaling).target_capacity == 100
    error_message = "capacity provider must not keep empty instances (50 doubles the pool instead of half-filling each instance)"
  }
}

run "api_task_role_can_reach_only_its_own_curation_prefix" {
  command = plan

  module {
    source = "./modules/ecs-environment"
  }

  variables {
    env = "dev"
  }

  assert {
    condition     = aws_iam_role_policy.api_task_curation.name == "curation-s3"
    error_message = "curation policy must keep the name applied manually from CloudShell so apply overwrites it instead of duplicating"
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.api_task_curation.policy).Statement[0].Resource == "arn:aws:s3:::test-storage-bucket/curation/dev/*"
    error_message = "object access must follow the curation environment (env), not storage_key_prefix"
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.api_task_curation.policy).Statement[1].Condition.StringLike["s3:prefix"] == "curation/dev/*"
    error_message = "list access must follow the curation environment (env)"
  }

  assert {
    condition     = !contains(jsondecode(aws_iam_role_policy.api_task_curation.policy).Statement[0].Action, "s3:DeleteObject")
    error_message = "the API task must not delete curation objects until a reference-safe cleanup flow exists"
  }
}

run "dev_spot_api_pool_uses_mixed_instances_across_arm64_types" {
  command = plan

  module {
    source = "./modules/ecs-environment"
  }

  variables {
    env            = "dev"
    spot_instances = true
  }

  assert {
    condition     = length(aws_autoscaling_group.pool["api"].mixed_instances_policy) == 1 && length(aws_autoscaling_group.pool["api"].launch_template) == 0
    error_message = "dev spot api pool must launch through a mixed instances policy, not a single launch template"
  }

  assert {
    condition     = one(one(aws_autoscaling_group.pool["api"].mixed_instances_policy).instances_distribution).spot_allocation_strategy == "price-capacity-optimized" && one(one(aws_autoscaling_group.pool["api"].mixed_instances_policy).instances_distribution).on_demand_percentage_above_base_capacity == 0
    error_message = "dev spot api pool must be 100% spot with price-capacity-optimized allocation"
  }

  assert {
    condition     = length(one(one(aws_autoscaling_group.pool["api"].mixed_instances_policy).launch_template).override) == 6 && aws_autoscaling_group.pool["api"].capacity_rebalance
    error_message = "dev spot api pool must offer six arm64 instance types and rebalance capacity"
  }

  assert {
    condition     = length(aws_launch_template.pool["api"].instance_market_options) == 0 && length(aws_launch_template.pool["batch"].instance_market_options) == 1
    error_message = "api template must leave the market to the mixed policy while batch keeps its spot market option"
  }
}

run "prod_on_demand_api_pool_keeps_single_launch_template" {
  command = plan

  module {
    source = "./modules/ecs-environment"
  }

  variables {
    env            = "prod"
    spot_instances = false
  }

  assert {
    condition     = length(aws_autoscaling_group.pool["api"].mixed_instances_policy) == 0 && length(aws_autoscaling_group.pool["api"].launch_template) == 1 && !aws_autoscaling_group.pool["api"].capacity_rebalance
    error_message = "on-demand api pool must keep the single launch template unchanged"
  }
}
