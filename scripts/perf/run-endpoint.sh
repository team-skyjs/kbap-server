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
target_catalog="$repo_dir/k6/endpoints/targets.json"
artifact_root="${PERFORMANCE_ARTIFACT_ROOT:-$repo_dir/artifacts/performance}"
jfr_start_script="${JFR_START_SCRIPT:-$script_dir/jfr-start.sh}"
jfr_stop_script="${JFR_STOP_SCRIPT:-$script_dir/jfr-stop.sh}"
fixture_cleanup_script="${FIXTURE_CLEANUP_SCRIPT:-$script_dir/cleanup-fixtures.sh}"

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
validate_dev_aws_environment
validate_base_url "$base_url"
required_commands=(python3 jq k6 aws session-manager-plugin)
[[ "$jfr_enabled" == "true" ]] && required_commands+=(docker)
require_commands "${required_commands[@]}"
validate_access_token
if [[ ! -f "$fixture_path" ]]; then
  echo "error: fixture file not found: $fixture_path" >&2
  exit 2
fi
if [[ ! -x "$jfr_start_script" || ! -x "$jfr_stop_script" ]]; then
  echo "error: JFR control scripts must be executable" >&2
  exit 2
fi
if [[ "${TEST_MODE:-false}" != "true" && "$fixture_cleanup_script" != "$script_dir/cleanup-fixtures.sh" ]]; then
  echo "error: FIXTURE_CLEANUP_SCRIPT override requires TEST_MODE=true" >&2
  exit 2
fi

if ! target_metadata=$(jq -er --arg target "$target" '
  .targets[] | select(.key == $target) |
  [.suite, .risk, .defaultProfile, .requestsPerIteration, .stateCapability, .objectCleanup] | @tsv
' "$target_catalog"); then
  echo "error: unknown TARGET: $target" >&2
  exit 2
fi
IFS=$'\t' read -r target_suite target_risk target_profile request_multiplier state_capability object_cleanup <<<"$target_metadata"
if [[ ! "$request_multiplier" =~ ^[1-9][0-9]{0,2}$ ]]; then
  echo "error: target request multiplier must be an integer from 1 to 999" >&2
  exit 2
fi
case "$state_capability" in
  none | snapshot-restore | tagged-cleanup | scan-cleanup) ;;
  *) echo "error: target has an invalid state cleanup capability" >&2; exit 2 ;;
esac
case "$object_cleanup" in
  none | imageCompleteFixtures | scanGeneratedFoodImageRefs) ;;
  *) echo "error: target has an invalid object cleanup capability" >&2; exit 2 ;;
esac
if [[ "$state_capability" == "none" && "$object_cleanup" != "none" ]]; then
  echo "error: object cleanup requires a state cleanup capability" >&2
  exit 2
fi
if [[ "$profile" != "smoke" && "$profile" != "$target_profile" ]]; then
  echo "error: PROFILE does not match the target default profile" >&2
  exit 2
fi
duration_seconds=0
case "$profile" in
  smoke)
    if [[ "$load" != "1" || "$extent" != "1" ]]; then
      echo "error: smoke profile requires load=1 and iterations=1" >&2
      exit 2
    fi
    ;;
  read | write)
    max_load=40
    max_duration=300
    if [[ "$profile" == "write" ]]; then
      max_load=10
      max_duration=120
    fi
    if [[ ! "$load" =~ ^[1-9][0-9]{0,1}$ ]] || ((load > max_load)); then
      echo "error: $profile profile load exceeds the approved cap" >&2
      exit 2
    fi
    if [[ ! "$extent" =~ ^([1-9][0-9]{0,5})([sm])$ ]]; then
      echo "error: $profile duration must be a positive s/m duration" >&2
      exit 2
    fi
    duration_amount=${BASH_REMATCH[1]}
    duration_unit=${BASH_REMATCH[2]}
    duration_seconds=$duration_amount
    [[ "$duration_unit" == "m" ]] && duration_seconds=$((duration_amount * 60))
    if ((duration_seconds > max_duration)); then
      echo "error: $profile duration exceeds the approved cap" >&2
      exit 2
    fi
    ;;
  external)
    if [[ ! "$load" =~ ^[1-9][0-9]?$ || ! "$extent" =~ ^[1-9][0-9]?$ ]] || \
      ((load > 10 || extent > 10)); then
      echo "error: external VUS and ITERATIONS must be integers from 1 to 10" >&2
      exit 2
    fi
    if ((load > 198 / extent)); then
      echo "error: external logical run must not exceed 200 projected iterations" >&2
      exit 2
    fi
    ;;
esac

if [[ "$target_suite" == "external" && "$profile" != "external" && "$profile" != "smoke" ]]; then
  echo "error: external targets require the external profile" >&2
  exit 2
fi

measurement_iterations=1
warmup_iterations=1
measurement_fixture_offset=2
case "$profile" in
  read | write)
    measurement_iterations=$((load * duration_seconds))
    warmup_iterations=$((load * 120))
    measurement_fixture_offset=$((1 + warmup_iterations))
    ;;
  external)
    measurement_iterations=$((load * extent))
    ;;
esac
total_iterations=$((1 + warmup_iterations + measurement_iterations))
max_http_requests=$((total_iterations * request_multiplier))
max_billable_provider_calls=0
provider_cost_cap=0
provider_quota=0
if [[ "$target_risk" == "cost" ]]; then
  max_billable_provider_calls=$max_http_requests
  provider_cost_cap=$max_http_requests
  provider_quota=200
fi

state_cleanup_required=false
if [[ "$state_capability" != "none" ]]; then
  state_cleanup_required=true
  if [[ ! -x "$fixture_cleanup_script" ]]; then
    echo "error: fixture cleanup script must be executable: $fixture_cleanup_script" >&2
    exit 2
  fi
  "$fixture_cleanup_script" --check "$state_capability" "$object_cleanup"
fi
validate_dev_aws_account

run_id="$campaign_id-$target"
report_dir="$artifact_root/$campaign_id/$target"
console_log="$report_dir/console.log"
state_snapshot="$report_dir/.state-snapshot.b64"
mkdir -p "$report_dir"
: >"$console_log"

stream_command() {
  local statuses result=0 restore_errexit=false
  [[ $- == *e* ]] && restore_errexit=true
  set +e
  "$@" 2>&1 | sanitize_performance_output | tee -a "$console_log"
  statuses=("${PIPESTATUS[@]}")
  if [[ "${statuses[0]}" -ne 0 ]]; then
    result=${statuses[0]}
  elif [[ "${statuses[1]}" -ne 0 ]]; then
    result=${statuses[1]}
  elif [[ "${statuses[2]}" -ne 0 ]]; then
    result=${statuses[2]}
  fi
  [[ "$restore_errexit" == "true" ]] && set -e
  return "$result"
}

announce_phase() {
  printf 'phase=%s run_id=%s target=%s\n' "$1" "$run_id" "$target" | tee -a "$console_log"
}

# CODE_DEPLOY 컨트롤러 서비스는 deployments 가 null 이라 `aws ecs wait services-stable` 웨이터가
# length(deployments) JMESPath 에서 실패한다 — describe-services 폴링으로 대체한다.
for stable_attempt in $(seq 1 30); do
  service_state=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    ecs describe-services \
    --cluster "$PERF_ECS_CLUSTER" \
    --services "$PERF_ECS_SERVICE" \
    --query 'services[0].[desiredCount,runningCount,pendingCount,taskDefinition]' \
    --output text)
  read -r desired_count running_count pending_count task_definition_arn <<<"$service_state"
  [[ "$desired_count" == "2" && "$running_count" == "2" && "$pending_count" == "0" ]] && break
  sleep 10
done
if [[ "$desired_count" != "2" || "$running_count" != "2" || "$pending_count" != "0" ]]; then
  echo "error: ECS service must be steady with desired=2, running=2, pending=0" >&2
  exit 3
fi
validate_dev_task_definition_arn "$task_definition_arn"

task_output=$(running_task_ids)
task_ids=()
while IFS= read -r task_id; do
  [[ -n "$task_id" ]] && task_ids+=("$task_id")
done <<<"$task_output"
resolve_task_ids "${task_ids[@]}" >/dev/null

image=$(aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
  ecs describe-task-definition \
  --task-definition "$task_definition_arn" \
  --query "taskDefinition.containerDefinitions[?name=='$PERF_ECS_CONTAINER'] | [0].image" \
  --output text)
if [[ -z "$image" || "$image" == "None" ]]; then
  echo "error: profiling image could not be resolved" >&2
  exit 3
fi
storage_bucket=""
if [[ "$object_cleanup" != "none" ]]; then
  storage_bucket=$(resolve_dev_storage_bucket "$task_definition_arn")
fi

git_sha=$(git -C "$repo_dir" rev-parse HEAD)
task_definition=${task_definition_arn##*/}
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
jfr_started=false
state_captured=false
cleanup_succeeded=true
[[ "$state_cleanup_required" == "true" ]] && cleanup_succeeded=false

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
    --arg profile "$profile" \
    --arg extent "$extent" \
    --arg state_capability "$state_capability" \
    --arg object_cleanup "$object_cleanup" \
    --argjson load "$load" \
    --argjson request_multiplier "$request_multiplier" \
    --argjson target_iterations "$total_iterations" \
    --argjson measurement_iterations "$measurement_iterations" \
    --argjson max_http_requests "$max_http_requests" \
    --argjson max_billable_provider_calls "$max_billable_provider_calls" \
    --argjson provider_cost_cap "$provider_cost_cap" \
    --argjson provider_quota "$provider_quota" \
    --argjson measurement_fixture_offset "$measurement_fixture_offset" \
    --argjson cleanup_succeeded "$cleanup_succeeded" \
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
        jfrEnabled: $jfr_enabled,
        profile: $profile,
        load: $load,
        extent: $extent,
        stateCapability: $state_capability,
        objectCleanup: $object_cleanup,
        cleanupSucceeded: $cleanup_succeeded,
        requestMultiplier: $request_multiplier,
        targetIterations: $target_iterations,
        measurementIterations: $measurement_iterations,
        maxHttpRequests: $max_http_requests,
        billableRequests: $max_billable_provider_calls,
        maxBillableProviderCalls: $max_billable_provider_calls,
        providerCostCap: $provider_cost_cap,
        providerQuota: $provider_quota,
        fixtureOffsets: {smoke: 0, warmup: 1, measurement: $measurement_fixture_offset}
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
  local fixture_cleanup_status=0
  local artifact_sanitize_status=0
  local finished_at
  trap - EXIT
  trap '' INT TERM
  set +e
  if [[ "$jfr_started" == "true" ]]; then
    announce_phase jfr-stop
    stream_command "$jfr_stop_script" "$run_id" "$report_dir" "${task_ids[@]}"
    cleanup_status=$?
  fi
  if [[ "$state_captured" == "true" ]]; then
    announce_phase fixture-restore
    stream_command env STORAGE_BUCKET="$storage_bucket" "$fixture_cleanup_script" \
      --restore "$run_id" "$target" "$state_capability" "$object_cleanup" "$state_snapshot" "$task_definition_arn"
    fixture_cleanup_status=$?
    [[ "$fixture_cleanup_status" -eq 0 ]] && cleanup_succeeded=true
  fi
  for artifact in "$console_log" "$report_dir/report.html" "$report_dir/summary.json"; do
    if ! sanitize_performance_file "$artifact"; then
      artifact_sanitize_status=1
    fi
  done
  finished_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  write_manifest "$finished_at"
  manifest_status=$?
  if [[ "$status" -eq 0 && "$cleanup_status" -ne 0 ]]; then
    status=$cleanup_status
  fi
  if [[ "$status" -eq 0 && "$fixture_cleanup_status" -ne 0 ]]; then
    status=$fixture_cleanup_status
  fi
  if [[ "$status" -eq 0 && "$artifact_sanitize_status" -ne 0 ]]; then
    status=$artifact_sanitize_status
  fi
  if [[ "$status" -eq 0 && "$manifest_status" -ne 0 ]]; then
    status=$manifest_status
  fi
  exit "$status"
}
trap cleanup_run EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_k6() {
  local phase=$1
  local selected_profile=$2
  local selected_load=$3
  local selected_extent=$4
  local fixture_offset=$5
  local args=(
    run --quiet
    -e "TARGET=$target"
    -e "BASE_URL=$base_url"
    -e "RUN_ID=$run_id"
    -e "REPORT_DIR=$report_dir"
    -e "FIXTURE_PATH=$fixture_path"
    -e "PROFILE=$selected_profile"
    -e "PHASE=$phase"
    -e "FIXTURE_OFFSET=$fixture_offset"
  )

  case "$selected_profile" in
    read | write)
      args+=(-e "RATE=$selected_load" -e "DURATION=$selected_extent")
      ;;
    external)
      args+=(-e "VUS=$selected_load" -e "ITERATIONS=$selected_extent")
      if [[ "$phase" == "warmup" ]]; then
        args+=(-e MAX_DURATION=2m)
      fi
      ;;
  esac
  announce_phase "$phase"
  stream_command k6 "${args[@]}" "$repo_dir/k6/endpoint.js"
}

if [[ "$state_cleanup_required" == "true" ]]; then
  announce_phase fixture-capture
  state_captured=true
  stream_command "$fixture_cleanup_script" \
    --capture "$run_id" "$target" "$state_capability" "$object_cleanup" "$fixture_path" "$state_snapshot"
fi

run_k6 smoke smoke 1 1 0
warmup_extent=$extent
warmup_load=$load
if [[ "$profile" == "read" || "$profile" == "write" ]]; then
  warmup_extent=2m
elif [[ "$target_suite" == "external" && "$profile" == "external" ]]; then
  warmup_load=1
  warmup_extent=1
fi
run_k6 warmup "$profile" "$warmup_load" "$warmup_extent" 1

if [[ "$jfr_enabled" == "true" ]]; then
  announce_phase jfr-start
  jfr_started=true
  stream_command "$jfr_start_script" "$run_id" "${task_ids[@]}"
  sleep 10
fi

set +e
run_k6 measurement "$profile" "$load" "$extent" "$measurement_fixture_offset"
main_status=$?

post_delay_status=0
if [[ "$jfr_started" == "true" ]]; then
  sleep 10
  post_delay_status=$?
fi
if [[ "$main_status" -ne 0 ]]; then
  exit "$main_status"
fi
exit "$post_delay_status"
