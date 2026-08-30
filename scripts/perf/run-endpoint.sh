#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
source "$script_dir/lib.sh"

if [[ $# -ne 4 ]]; then
  echo "usage: $0 TARGET PROFILE RATE_OR_VUS DURATION_OR_ITERATIONS" >&2
  exit 2
fi

target=$1
profile=$2
load=$3
extent=$4
campaign_id="${CAMPAIGN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
jfr_enabled="${JFR_ENABLED:-true}"
base_url="${BASE_URL:-https://dev.kbap.site}"
fixture_path="${FIXTURE_PATH:-$repo_dir/k6/fixtures/dev.json}"
artifact_root="${PERFORMANCE_ARTIFACT_ROOT:-$repo_dir/artifacts/performance}"
jfr_start_script="${JFR_START_SCRIPT:-$script_dir/jfr-start.sh}"
jfr_stop_script="${JFR_STOP_SCRIPT:-$script_dir/jfr-stop.sh}"

if [[ ! "$target" =~ ^[a-zA-Z0-9._-]+$ || ! "$campaign_id" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "error: TARGET and CAMPAIGN_ID must match ^[a-zA-Z0-9._-]+$" >&2
  exit 2
fi
case "$profile" in
  smoke | read | write | external) ;;
  *)
    echo "error: PROFILE must be smoke, read, write, or external" >&2
    exit 2
    ;;
esac
if [[ "$jfr_enabled" != "true" && "$jfr_enabled" != "false" ]]; then
  echo "error: JFR_ENABLED must be true or false" >&2
  exit 2
fi
if [[ "$jfr_enabled" == "false" && "$profile" != "smoke" ]]; then
  echo "error: JFR_ENABLED=false is only allowed with the smoke profile" >&2
  exit 2
fi
if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  echo "error: ACCESS_TOKEN is required" >&2
  exit 2
fi
if [[ ! -f "$fixture_path" ]]; then
  echo "error: fixture file not found: $fixture_path" >&2
  exit 2
fi
if [[ ! -x "$jfr_start_script" || ! -x "$jfr_stop_script" ]]; then
  echo "error: JFR control scripts must be executable" >&2
  exit 2
fi

run_id="$campaign_id-$target"
report_dir="$artifact_root/$campaign_id/$target"
console_log="$report_dir/console.log"
mkdir -p "$report_dir"
: >"$console_log"

aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
  ecs wait services-stable \
  --cluster "$PERF_ECS_CLUSTER" \
  --services "$PERF_ECS_SERVICE"

service_state=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
  ecs describe-services \
  --cluster "$PERF_ECS_CLUSTER" \
  --services "$PERF_ECS_SERVICE" \
  --query 'services[0].[desiredCount,runningCount,pendingCount,taskDefinition]' \
  --output text)
read -r desired_count running_count pending_count task_definition_arn <<<"$service_state"
if [[ "$desired_count" != "2" || "$running_count" != "2" || "$pending_count" != "0" ]]; then
  echo "error: ECS service must be steady with desired=2, running=2, pending=0" >&2
  exit 3
fi

task_output=$(running_task_ids)
task_ids=()
while IFS= read -r task_id; do
  [[ -n "$task_id" ]] && task_ids+=("$task_id")
done <<<"$task_output"
require_two_tasks "${task_ids[@]}"

image=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
  ecs describe-task-definition \
  --task-definition "$task_definition_arn" \
  --query "taskDefinition.containerDefinitions[?name=='$PERF_ECS_CONTAINER'] | [0].image" \
  --output text)
if [[ -z "$image" || "$image" == "None" ]]; then
  echo "error: profiling image could not be resolved" >&2
  exit 3
fi

git_sha=$(git -C "$repo_dir" rev-parse HEAD)
task_definition=${task_definition_arn##*/}
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
jfr_started=false

# shellcheck disable=SC2329
write_manifest() {
  local finished_at=$1
  if ! jq -n \
    --arg campaign_id "$campaign_id" \
    --arg run_id "$run_id" \
    --arg target "$target" \
    --arg base_url "$base_url" \
    --arg git_sha "$git_sha" \
    --arg task_definition "$task_definition" \
    --arg image "$image" \
    --arg task_one "${task_ids[0]}" \
    --arg task_two "${task_ids[1]}" \
    --arg started_at "$started_at" \
    --arg finished_at "$finished_at" \
    --argjson jfr_enabled "$jfr_enabled" '
      {
        campaignId: $campaign_id,
        runId: $run_id,
        target: $target,
        baseUrl: $base_url,
        gitSha: $git_sha,
        taskDefinition: $task_definition,
        image: $image,
        taskIds: [$task_one, $task_two],
        startedAt: $started_at,
        finishedAt: $finished_at,
        jfrEnabled: $jfr_enabled
      }
    ' >"$report_dir/manifest.json.tmp"; then
    rm -f "$report_dir/manifest.json.tmp"
    return 1
  fi
  mv "$report_dir/manifest.json.tmp" "$report_dir/manifest.json"
}

# shellcheck disable=SC2329
cleanup_run() {
  local status=$?
  local cleanup_status=0
  local finished_at
  trap - EXIT
  set +e
  if [[ "$jfr_started" == "true" ]]; then
    "$jfr_stop_script" "$run_id" "$report_dir" >>"$console_log" 2>&1
    cleanup_status=$?
  fi
  finished_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  write_manifest "$finished_at"
  manifest_status=$?
  if [[ "$status" -eq 0 && "$cleanup_status" -ne 0 ]]; then
    status=$cleanup_status
  fi
  if [[ "$status" -eq 0 && "$manifest_status" -ne 0 ]]; then
    status=$manifest_status
  fi
  exit "$status"
}
trap cleanup_run EXIT

run_k6() {
  local phase=$1
  local selected_profile=$2
  local selected_extent=$3
  local args=(
    run --quiet
    -e "TARGET=$target"
    -e "BASE_URL=$base_url"
    -e "ACCESS_TOKEN=$ACCESS_TOKEN"
    -e "RUN_ID=$run_id"
    -e "REPORT_DIR=$report_dir"
    -e "FIXTURE_PATH=$fixture_path"
    -e "PROFILE=$selected_profile"
    -e "PHASE=$phase"
  )

  case "$selected_profile" in
    read | write)
      args+=(-e "RATE=$load" -e "DURATION=$selected_extent")
      ;;
    external)
      args+=(-e "VUS=$load" -e "ITERATIONS=$selected_extent")
      if [[ "$phase" == "warmup" ]]; then
        args+=(-e MAX_DURATION=2m)
      fi
      ;;
  esac
  k6 "${args[@]}" "$repo_dir/k6/endpoint.js" >>"$console_log" 2>&1
}

run_k6 smoke smoke 1
warmup_extent=$extent
if [[ "$profile" == "read" || "$profile" == "write" ]]; then
  warmup_extent=2m
fi
run_k6 warmup "$profile" "$warmup_extent"

if [[ "$jfr_enabled" == "true" ]]; then
  "$jfr_start_script" "$run_id" >>"$console_log" 2>&1
  jfr_started=true
  sleep 10
fi

set +e
run_k6 measurement "$profile" "$extent"
main_status=$?
set -e

if [[ "$jfr_started" == "true" ]]; then
  sleep 10
fi
exit "$main_status"
