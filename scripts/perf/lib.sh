#!/usr/bin/env bash

PERF_AWS_PROFILE="${AWS_PROFILE:-kbap-infra}"
PERF_AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PERF_ECS_CLUSTER="${ECS_CLUSTER:-kbap-dev-ecs-cluster}"
PERF_ECS_SERVICE="${ECS_SERVICE:-kbap-dev-ecs-api}"
PERF_ECS_CONTAINER="${ECS_CONTAINER:-api}"
PERF_ARTIFACT_BUCKET="${PERFORMANCE_ARTIFACT_BUCKET:-kbap-dev-ecs-performance-artifacts}"

running_task_ids() {
  local task_arns task_arn

  task_arns=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    ecs list-tasks \
    --cluster "$PERF_ECS_CLUSTER" \
    --service-name "$PERF_ECS_SERVICE" \
    --desired-status RUNNING \
    --query 'taskArns[]' \
    --output text) || return $?

  while IFS= read -r task_arn; do
    [[ -z "$task_arn" || "$task_arn" == "None" ]] && continue
    printf '%s\n' "${task_arn##*/}"
  done < <(printf '%s\n' "$task_arns" | tr '\t' '\n')
}

require_two_tasks() {
  if [[ $# -ne 2 ]]; then
    echo "error: expected exactly two running tasks, found $#" >&2
    return 3
  fi
}

execute_in_task() {
  local task_id=$1
  local command=$2

  aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    ecs execute-command \
    --cluster "$PERF_ECS_CLUSTER" \
    --task "$task_id" \
    --container "$PERF_ECS_CONTAINER" \
    --interactive \
    --command "$command"
}

summarize_jfr() {
  local file=$1

  docker run --rm \
    -v "$(dirname "$file"):/artifacts:ro" \
    --entrypoint jfr \
    eclipse-temurin:21-jdk \
    summary "/artifacts/$(basename "$file")"
}
