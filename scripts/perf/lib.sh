#!/usr/bin/env bash

PERF_AWS_PROFILE="${AWS_PROFILE:-kbap-infra}"
PERF_AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PERF_AWS_ACCOUNT_ID=118178010621
PERF_ECS_CLUSTER="${ECS_CLUSTER:-kbap-dev-ecs-cluster}"
PERF_ECS_SERVICE="${ECS_SERVICE:-kbap-dev-ecs-api}"
PERF_ECS_CONTAINER="${ECS_CONTAINER:-api}"
PERF_ARTIFACT_BUCKET="${PERFORMANCE_ARTIFACT_BUCKET:-kbap-dev-ecs-performance-artifacts}"
PERF_REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

require_commands() {
  local command
  for command in "$@"; do
    if ! command -v "$command" >/dev/null 2>&1; then
      echo "error: required command not found: $command" >&2
      return 2
    fi
  done
}

validate_dev_aws_environment() {
  if [[ "$PERF_AWS_PROFILE" != "kbap-infra" || "$PERF_AWS_REGION" != "ap-northeast-2" || \
    "$PERF_ECS_CLUSTER" != "kbap-dev-ecs-cluster" || "$PERF_ECS_SERVICE" != "kbap-dev-ecs-api" || \
    "$PERF_ECS_CONTAINER" != "api" || "$PERF_ARTIFACT_BUCKET" != "kbap-dev-ecs-performance-artifacts" ]]; then
    echo "error: performance runner is restricted to the approved dev AWS environment" >&2
    return 2
  fi
}

validate_dev_aws_account() {
  local account_id
  account_id=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    sts get-caller-identity --query Account --output text) || return $?
  if [[ "$account_id" != "$PERF_AWS_ACCOUNT_ID" ]]; then
    echo "error: performance runner is restricted to the approved dev AWS account" >&2
    return 2
  fi
}

validate_dev_task_definition_arn() {
  local task_definition_arn=$1
  if [[ ! "$task_definition_arn" =~ ^arn:aws:ecs:ap-northeast-2:118178010621:task-definition/kbap-dev-ecs-api:[1-9][0-9]*$ ]]; then
    echo "error: ECS task definition must belong to the approved dev service account" >&2
    return 2
  fi
}

resolve_dev_storage_bucket() {
  local task_definition_arn=$1
  local storage_bucket
  validate_dev_task_definition_arn "$task_definition_arn" || return $?
  storage_bucket=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    ecs describe-task-definition \
    --task-definition "$task_definition_arn" \
    --query "taskDefinition.containerDefinitions[?name=='$PERF_ECS_CONTAINER'] | [0].environment[?name=='STORAGE_BUCKET'] | [0].value" \
    --output text) || return $?
  if [[ -z "$storage_bucket" || "$storage_bucket" == "None" ]]; then
    echo "error: approved dev task definition has no STORAGE_BUCKET" >&2
    return 2
  fi
  if [[ -n "${STORAGE_BUCKET:-}" && "$STORAGE_BUCKET" != "$storage_bucket" ]]; then
    echo "error: inherited STORAGE_BUCKET does not match the approved dev task definition" >&2
    return 2
  fi
  printf '%s\n' "$storage_bucket"
}

validate_base_url() {
  local base_url=$1
  local test_mode="${TEST_MODE:-false}"
  if [[ "$test_mode" != "true" && "$test_mode" != "false" ]]; then
    echo "error: TEST_MODE must be true or false" >&2
    return 2
  fi
  if [[ "$base_url" == "https://dev.kbap.site" ]]; then
    return 0
  fi
  if [[ "$test_mode" == "true" && "$base_url" =~ ^http://127\.0\.0\.1(:[1-9][0-9]{0,4})?$ ]]; then
    return 0
  fi
  echo "error: BASE_URL must be https://dev.kbap.site; loopback requires TEST_MODE=true" >&2
  return 2
}

validate_access_token() {
  if [[ -z "${JWT_SECRET:-}" || -z "${ACCESS_TOKEN:-}" ]]; then
    echo "error: JWT_SECRET and ACCESS_TOKEN are required" >&2
    return 2
  fi
  if ! python3 - <<'PY'
import base64
import binascii
import hashlib
import hmac
import json
import os
import time

def decode(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))

try:
    token = os.environ["ACCESS_TOKEN"]
    secret = os.environ["JWT_SECRET"].encode()
    encoded_header, encoded_payload, encoded_signature = token.split(".")
    header = json.loads(decode(encoded_header))
    payload = json.loads(decode(encoded_payload))
    signature = decode(encoded_signature)
    expected = hmac.new(secret, f"{encoded_header}.{encoded_payload}".encode(), hashlib.sha256).digest()
    now = int(time.time())
    valid = (
        isinstance(header, dict)
        and isinstance(payload, dict)
        and header.get("alg") == "HS256"
        and hmac.compare_digest(signature, expected)
        and payload.get("sub") == "35"
        and payload.get("token_type") == "ACCESS"
        and isinstance(payload.get("exp"), int)
        and payload["exp"] > now
        and ("nbf" not in payload or isinstance(payload["nbf"], int) and payload["nbf"] <= now)
    )
except (AttributeError, binascii.Error, KeyError, ValueError, TypeError, json.JSONDecodeError, UnicodeDecodeError):
    valid = False
raise SystemExit(0 if valid else 1)
PY
  then
    echo "error: ACCESS_TOKEN must be a valid member 35 ACCESS JWT" >&2
    return 2
  fi
}

sanitize_performance_output() {
  python3 -u -c '
import os
import re
import sys

sys.path.insert(0, sys.argv[1])
from tools.perf_dashboard.events import sanitize_line

url_pattern = re.compile(r"(?:https?|s3)://[^\s\"\x27<>]+", re.IGNORECASE)
secret_name_pattern = re.compile(r"TOKEN|SECRET|PASSWORD|PASSPHRASE|(?:^|_)PWD(?:$|_)|CREDENTIAL|ACCESS_KEY|PRIVATE_KEY|SIGNING_KEY|AUTH", re.IGNORECASE)
secrets = [value for name, value in os.environ.items() if value and secret_name_pattern.search(name)]
for line in sys.stdin:
    line = sanitize_line(line)
    for secret in secrets:
        line = line.replace(secret, "[REDACTED]")
    line = url_pattern.sub("[REDACTED_URL]", line)
    sys.stdout.write(line + "\n")
' "$PERF_REPO_DIR"
}

sanitize_performance_file() {
  local file=$1
  local temporary_file
  [[ -f "$file" ]] || return 0
  temporary_file="$file.sanitized.$$"
  if ! sanitize_performance_output <"$file" >"$temporary_file"; then
    rm -f "$temporary_file"
    return 1
  fi
  mv "$temporary_file" "$file"
}

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

resolve_task_ids() {
  local task_output task_id
  local task_ids=()

  if [[ $# -eq 0 ]]; then
    task_output=$(running_task_ids) || return $?
    while IFS= read -r task_id; do
      [[ -n "$task_id" ]] && task_ids+=("$task_id")
    done <<<"$task_output"
  else
    task_ids=("$@")
  fi

  require_two_tasks "${task_ids[@]}" || return $?
  if [[ "${task_ids[0]}" == "${task_ids[1]}" ]]; then
    echo "error: ECS task IDs must be distinct" >&2
    return 2
  fi
  for task_id in "${task_ids[@]}"; do
    if [[ ! "$task_id" =~ ^[a-fA-F0-9]{32}$ ]]; then
      echo "error: invalid ECS task ID: $task_id" >&2
      return 2
    fi
    printf '%s\n' "$task_id"
  done
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
