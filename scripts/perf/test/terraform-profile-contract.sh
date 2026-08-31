#!/usr/bin/env bash
set -euo pipefail

rg -q 'api_execute_command_enabled' iac/terraform/variables.tf
rg -q 'enable_execute_command[[:space:]]*=[[:space:]]*local.performance_profiling_enabled' iac/terraform/modules/ecs-environment/api.tf
rg -q 'performance_artifact_retention_days' iac/terraform/modules/ecs-environment/variables.tf
rg -q 'aws_s3_bucket.*performance_artifacts' iac/terraform/modules/ecs-environment/performance-artifacts.tf
rg -q 'ssmmessages:CreateControlChannel' iac/terraform/modules/ecs-environment/iam.tf
rg -q 'performance_artifact_bucket_name' iac/terraform/outputs.tf
rg -q 'performance_profiling_enabled[[:space:]]*=[[:space:]]*var.env == "dev" && var.api_execute_command_enabled' iac/terraform/modules/ecs-environment/data.tf
rg -q 'condition[[:space:]]*=[[:space:]]*!var.api_execute_command_enabled || var.env == "dev"' iac/terraform/modules/ecs-environment/api.tf
rg -q 'performance profiling can only be enabled when env is dev' iac/terraform/modules/ecs-environment/api.tf
test "$(rg -c 'count[[:space:]]*=[[:space:]]*local.performance_profiling_enabled \? 1 : 0' iac/terraform/modules/ecs-environment/performance-artifacts.tf)" -eq 4
test "$(rg -c 'for_each[[:space:]]*=[[:space:]]*local.performance_profiling_enabled \? \[1\] : \[\]' iac/terraform/modules/ecs-environment/iam.tf)" -eq 2
rg -Uq 'sid[[:space:]]*=[[:space:]]*"PutPerformanceArtifact"\n[[:space:]]*actions[[:space:]]*=[[:space:]]*\["s3:PutObject"\]\n[[:space:]]*resources[[:space:]]*=[[:space:]]*\["\$\{aws_s3_bucket\.performance_artifacts\[0\]\.arn\}/\*"\]' iac/terraform/modules/ecs-environment/iam.tf
rg -q 'value[[:space:]]*=[[:space:]]*local.performance_profiling_enabled \? aws_s3_bucket.performance_artifacts\[0\].bucket : null' iac/terraform/modules/ecs-environment/outputs.tf
rg -q 'sse_algorithm[[:space:]]*=[[:space:]]*"AES256"' iac/terraform/modules/ecs-environment/performance-artifacts.tf
rg -q 'filter[[:space:]]*\{\}' iac/terraform/modules/ecs-environment/performance-artifacts.tf
test "$(rg -c 'block_public_acls|block_public_policy|ignore_public_acls|restrict_public_buckets' iac/terraform/modules/ecs-environment/performance-artifacts.tf)" -eq 4
