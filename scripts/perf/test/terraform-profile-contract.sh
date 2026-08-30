#!/usr/bin/env bash
set -euo pipefail

rg -q 'api_execute_command_enabled' iac/terraform/variables.tf
rg -q 'enable_execute_command[[:space:]]*=[[:space:]]*var.api_execute_command_enabled' iac/terraform/modules/ecs-environment/api.tf
rg -q 'performance_artifact_retention_days' iac/terraform/modules/ecs-environment/variables.tf
rg -q 'aws_s3_bucket.*performance_artifacts' iac/terraform/modules/ecs-environment/performance-artifacts.tf
rg -q 'ssmmessages:CreateControlChannel' iac/terraform/modules/ecs-environment/iam.tf
rg -q 'performance_artifact_bucket_name' iac/terraform/outputs.tf
