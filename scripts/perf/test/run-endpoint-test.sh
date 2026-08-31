#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
runner="$repo_dir/scripts/perf/run-endpoint.sh"
real_k6="$(command -v k6)"
temp_dir="$(mktemp -d)"
fake_bin="$temp_dir/bin"
fixture="$temp_dir/dev.json"
artifact_root="$temp_dir/artifacts/performance"
event_log="$temp_dir/events"
task_one="11111111111111111111111111111111"
task_two="22222222222222222222222222222222"
campaign_id="20260831T120000Z"
mock_pid=""
jwt_secret="jwt-super-secret"

cleanup() {
  if [[ -n "$mock_pid" ]]; then
    kill "$mock_pid" 2>/dev/null || true
    wait "$mock_pid" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

mkdir -p "$fake_bin" "$artifact_root"
printf '{}\n' >"$fixture"

mint_token() {
  local subject=$1
  local token_type=$2
  JWT_SECRET="$jwt_secret" TOKEN_SUBJECT="$subject" TOKEN_TYPE="$token_type" python3 - <<'PY'
import base64, hashlib, hmac, json, os, time

def encode(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()

header = encode({"alg": "HS256", "typ": "JWT"})
payload = encode({"sub": os.environ["TOKEN_SUBJECT"], "token_type": os.environ["TOKEN_TYPE"], "iat": int(time.time()), "exp": int(time.time()) + 3600})
signature = base64.urlsafe_b64encode(hmac.new(os.environ["JWT_SECRET"].encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
print(f"{header}.{payload}.{signature}")
PY
}

access_token="$(mint_token 35 ACCESS)"
wrong_subject_token="$(mint_token 36 ACCESS)"
refresh_token="$(mint_token 35 REFRESH)"
token_signature=${access_token##*.}
bad_signature_prefix=A
[[ "${token_signature:0:1}" == "A" ]] && bad_signature_prefix=B
bad_signature_token="${access_token%.*}.${bad_signature_prefix}${token_signature:1}"

cat >"$fake_bin/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >>"$FAKE_EVENT_LOG"
if [[ " $* " == *" sts get-caller-identity "* ]]; then
  printf '%s\n' "${FAKE_AWS_ACCOUNT_ID:-118178010621}"
  exit 0
fi
if [[ " $* " == *" ecs wait services-stable "* ]]; then
  exit 0
fi
if [[ " $* " == *" ecs describe-services "* ]]; then
  printf '%s\t%s\t%s\t%s\n' "${FAKE_DESIRED_COUNT:-2}" "${FAKE_RUNNING_COUNT:-2}" "${FAKE_PENDING_COUNT:-0}" 'arn:aws:ecs:ap-northeast-2:118178010621:task-definition/kbap-dev-ecs-api:42'
  exit 0
fi
if [[ " $* " == *" ecs list-tasks "* ]]; then
  if [[ "${FAKE_INVALID_TASK_ID:-0}" == "1" ]]; then
    printf '%s\n%s\n' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/invalid-task' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/22222222222222222222222222222222'
    exit 0
  fi
  case "${FAKE_TASK_COUNT:-2}" in
    1) printf '%s\n' 'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/11111111111111111111111111111111' ;;
    2) printf '%s\n%s\n' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/11111111111111111111111111111111' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/22222222222222222222222222222222' ;;
    3) printf '%s\n%s\n%s\n' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/11111111111111111111111111111111' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/22222222222222222222222222222222' \
      'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/33333333333333333333333333333333' ;;
  esac
  exit 0
fi
if [[ " $* " == *" ecs describe-task-definition "* ]]; then
  if [[ " $* " == *"STORAGE_BUCKET"* ]]; then
    printf '%s\n' 'kbap-dev-storage-assets'
  else
    printf '%s\n' '118178010621.dkr.ecr.ap-northeast-2.amazonaws.com/kbap-api:profile-abc123'
  fi
  exit 0
fi
printf 'unexpected aws invocation: %s\n' "$*" >&2
exit 64
AWS

cat >"$fake_bin/k6" <<'K6'
#!/usr/bin/env bash
set -euo pipefail
phase=""
profile=""
target=""
run_id=""
report_dir=""
duration=""
vus=""
iterations=""
max_duration=""
access_token_arg=false
fixture_offset=""
while (($#)); do
  if [[ "$1" == "-e" ]]; then
    pair=$2
    case "$pair" in
      PHASE=*) phase=${pair#*=} ;;
      PROFILE=*) profile=${pair#*=} ;;
      TARGET=*) target=${pair#*=} ;;
      RUN_ID=*) run_id=${pair#*=} ;;
      REPORT_DIR=*) report_dir=${pair#*=} ;;
      DURATION=*) duration=${pair#*=} ;;
      VUS=*) vus=${pair#*=} ;;
      ITERATIONS=*) iterations=${pair#*=} ;;
      MAX_DURATION=*) max_duration=${pair#*=} ;;
      ACCESS_TOKEN=*) access_token_arg=true ;;
      FIXTURE_OFFSET=*) fixture_offset=${pair#*=} ;;
    esac
    shift 2
    continue
  fi
  shift
done
if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  printf '%s\n' 'ACCESS_TOKEN was not inherited by k6' >&2
  exit 67
fi
printf 'k6 phase=%s profile=%s target=%s run=%s duration=%s vus=%s iterations=%s max_duration=%s access_token_arg=%s fixture_offset=%s\n' \
  "$phase" "$profile" "$target" "$run_id" "$duration" "$vus" "$iterations" "$max_duration" "$access_token_arg" "$fixture_offset" >>"$FAKE_EVENT_LOG"
if [[ "${FAKE_EMIT_SENSITIVE_OUTPUT:-0}" == "1" ]]; then
  printf 'phase=%s Authorization: Bearer %s ACCESS_TOKEN=%s AWS_SECRET_ACCESS_KEY=aws-log-secret OPENAI_API_KEY=provider-log-secret s3://private-bucket/%s/task.jfr https://private-bucket.s3.ap-northeast-2.amazonaws.com/task.jfr?X-Amz-Signature=signed-value http://internal.example.test/path\n' \
    "$phase" "$ACCESS_TOKEN" "$ACCESS_TOKEN" "$run_id"
  printf '{"clientSecret":"json-log-secret","keyboard":"keep"}\n'
  printf 'raw-secrets %s %s\n' "${AWS_SECRET_ACCESS_KEY:-}" "${MYSQL_PWD:-}"
fi
mkdir -p "$report_dir"
if [[ "${FAKE_EMIT_SENSITIVE_OUTPUT:-0}" == "1" ]]; then
  printf '<html>%s %s s3://private-bucket/report https://private-bucket.s3.amazonaws.com/report?X-Amz-Signature=hidden</html>\n' "$ACCESS_TOKEN" "$JWT_SECRET" >"$report_dir/report.html"
  printf '{"token":"%s","secret":"%s","artifact":"s3://private-bucket/summary","url":"http://private.example.test/summary","AWS_SECRET_ACCESS_KEY":"summary-aws-secret"}\n' \
    "$ACCESS_TOKEN" "$JWT_SECRET" >"$report_dir/summary.json"
else
  printf '<html>fake</html>\n' >"$report_dir/report.html"
  printf '{"fake":true}\n' >"$report_dir/summary.json"
fi
if [[ "$phase" == "measurement" ]]; then
  if [[ "${FAKE_CANCEL_MEASUREMENT:-0}" == "1" ]]; then
    kill -TERM "$PPID"
    exit 143
  fi
  if [[ "${FAKE_MANIFEST_WRITE_FAILURE:-0}" == "1" ]]; then
    chmod u-w "$report_dir"
  fi
  exit "${FAKE_K6_MAIN_EXIT:-0}"
fi
if [[ "$phase" == "smoke" ]]; then
  exit "${FAKE_K6_SMOKE_EXIT:-0}"
fi
if [[ "$phase" == "warmup" ]]; then
  exit "${FAKE_K6_WARMUP_EXIT:-0}"
fi
K6

cat >"$fake_bin/jfr-start.sh" <<'JFR_START'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_REQUIRE_TASK_IDS:-0}" == "1" && $# -ne 3 ]]; then
  printf 'JFR start did not receive the runner task snapshot: argc=%s\n' "$#" >&2
  exit 65
fi
printf 'jfr-start %s task_one=%s task_two=%s\n' "$1" "${2:-rediscovered}" "${3:-rediscovered}" >>"$FAKE_EVENT_LOG"
if [[ "${FAKE_EMIT_SENSITIVE_OUTPUT:-0}" == "1" ]]; then
  printf 'phase=jfr-start JWT_SECRET=%s s3://private-bucket/%s/start\n' "$JWT_SECRET" "$1"
fi
exit "${FAKE_JFR_START_EXIT:-0}"
JFR_START

cat >"$fake_bin/jfr-stop.sh" <<'JFR_STOP'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_REQUIRE_TASK_IDS:-0}" == "1" && $# -ne 4 ]]; then
  printf 'JFR stop did not receive the runner task snapshot: argc=%s\n' "$#" >&2
  exit 66
fi
printf 'jfr-stop %s task_one=%s task_two=%s\n' "$1" "${3:-rediscovered}" "${4:-rediscovered}" >>"$FAKE_EVENT_LOG"
if [[ "${FAKE_EMIT_SENSITIVE_OUTPUT:-0}" == "1" ]]; then
  printf 'phase=jfr-stop ACCESS_TOKEN=%s s3://private-bucket/%s/stop\n' "$ACCESS_TOKEN" "$1"
fi
mkdir -p "$2"
task_one=${3:-11111111111111111111111111111111}
task_two=${4:-22222222222222222222222222222222}
rm -f "$2/task-$task_one.jfr" "$2/task-$task_two.jfr"
status=${FAKE_JFR_STOP_EXIT:-0}
for task_id in "$task_one" "$task_two"; do
  printf 'jfr-stop-task %s\n' "$task_id" >>"$FAKE_EVENT_LOG"
  if [[ -n "${FAKE_JFR_STOP_FAIL_TASK:-}" && "$task_id" == "$FAKE_JFR_STOP_FAIL_TASK" ]]; then
    [[ "$status" -eq 0 ]] && status=31
    continue
  fi
  printf 'fake jfr\n' >"$2/task-$task_id.jfr"
done
exit "$status"
JFR_STOP

cat >"$fake_bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
set -euo pipefail
printf 'sleep %s\n' "$1" >>"$FAKE_EVENT_LOG"
if [[ "${FAKE_FAIL_POST_DELAY:-0}" == "1" ]] && grep -q '^k6 phase=measurement ' "$FAKE_EVENT_LOG"; then
  exit 29
fi
SLEEP

cat >"$fake_bin/session-manager-plugin" <<'PLUGIN'
#!/usr/bin/env bash
exit 0
PLUGIN

cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
exit 0
DOCKER

cat >"$fake_bin/cleanup-fixtures.sh" <<'CLEANUP'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "--check" ]]; then
  printf 'cleanup-check\n' >>"$FAKE_EVENT_LOG"
  exit "${FAKE_CLEANUP_CHECK_EXIT:-0}"
fi
if [[ "${1:-}" == "--capture" && $# -eq 7 ]]; then
  printf 'cleanup-capture run=%s target=%s state=%s object=%s fixture=%s snapshot=%s\n' \
    "$2" "$3" "$4" "$5" "$6" "$7" >>"$FAKE_EVENT_LOG"
  printf 'fake-snapshot\n' >"$7"
  exit "${FAKE_CLEANUP_CAPTURE_EXIT:-0}"
fi
if [[ "${1:-}" == "--restore" && $# -eq 7 ]]; then
  printf 'cleanup-restore run=%s target=%s state=%s object=%s snapshot=%s\n' \
    "$2" "$3" "$4" "$5" "$6" >>"$FAKE_EVENT_LOG"
  status=${FAKE_CLEANUP_RESTORE_EXIT:-0}
  [[ "$status" -eq 0 ]] && rm -f "$6"
  exit "$status"
fi
exit 64
CLEANUP

chmod +x "$fake_bin/aws" "$fake_bin/k6" "$fake_bin/jfr-start.sh" "$fake_bin/jfr-stop.sh" "$fake_bin/sleep" \
  "$fake_bin/session-manager-plugin" "$fake_bin/docker" "$fake_bin/cleanup-fixtures.sh"

run_runner() {
  PATH="$fake_bin:$PATH" \
    FAKE_EVENT_LOG="$event_log" \
    PERFORMANCE_ARTIFACT_ROOT="$artifact_root" \
    FIXTURE_PATH="$fixture" \
    FIXTURE_CLEANUP_SCRIPT="$fake_bin/cleanup-fixtures.sh" \
    JFR_START_SCRIPT="$fake_bin/jfr-start.sh" \
    JFR_STOP_SCRIPT="$fake_bin/jfr-stop.sh" \
    TEST_MODE=true \
    STORAGE_BUCKET='' \
    ACCESS_TOKEN="$access_token" \
    JWT_SECRET="$jwt_secret" \
    "$@"
}

assert_nonzero() {
  set +e
  run_runner "$@" >"$temp_dir/stdout" 2>"$temp_dir/stderr"
  status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    printf 'expected failure: %s\n' "$*" >&2
    exit 1
  fi
}

assert_external_input_rejected() {
  local input_load=$1
  local input_extent=$2
  local label=$3
  : >"$event_log"
  set +e
  run_runner env CAMPAIGN_ID="$campaign_id" "$runner" scan-v2-krw external "$input_load" "$input_extent" \
    >"$temp_dir/$label.stdout" 2>"$temp_dir/$label.stderr"
  local status=$?
  set -e
  if [[ "$status" -eq 0 ]] || grep -q '^k6 ' "$event_log"; then
    printf 'unsafe external input reached k6: label=%s exit=%s calls=%s\n' \
      "$label" "$status" "$(grep -c '^k6 ' "$event_log" || true)" >&2
    exit 1
  fi
}

assert_preflight_rejected() {
  local label=$1
  shift
  : >"$event_log"
  set +e
  run_runner env "$@" "$runner" app-version read 1 1s \
    >"$temp_dir/$label.stdout" 2>"$temp_dir/$label.stderr"
  local status=$?
  set -e
  if [[ "$status" -eq 0 ]] || rg -q '^(aws|k6|jfr-|cleanup-(capture|restore))' "$event_log"; then
    printf 'unsafe preflight reached an action: label=%s exit=%s\n' "$label" "$status" >&2
    exit 1
  fi
}

assert_profile_rejected() {
  local target=$1
  local profile=$2
  local selected_load=$3
  local selected_extent=$4
  local label=$5
  : >"$event_log"
  set +e
  run_runner env CAMPAIGN_ID="$campaign_id" "$runner" "$target" "$profile" "$selected_load" "$selected_extent" \
    >"$temp_dir/$label.stdout" 2>"$temp_dir/$label.stderr"
  local status=$?
  set -e
  if [[ "$status" -eq 0 ]] || rg -q '^(aws|k6|jfr-|cleanup-(capture|restore))' "$event_log"; then
    printf 'invalid profile input reached an action: label=%s exit=%s\n' "$label" "$status" >&2
    exit 1
  fi
}

assert_preflight_rejected prod-base BASE_URL=https://api.kbap.site
assert_preflight_rejected prod-profile AWS_PROFILE=prod
assert_preflight_rejected prod-cluster ECS_CLUSTER=kbap-prod-ecs-cluster
assert_preflight_rejected prod-service ECS_SERVICE=kbap-prod-ecs-api
assert_preflight_rejected prod-container ECS_CONTAINER=prod-api
assert_preflight_rejected prod-bucket PERFORMANCE_ARTIFACT_BUCKET=kbap-prod-performance-artifacts
assert_preflight_rejected loopback-without-test TEST_MODE=false BASE_URL=http://127.0.0.1:18081
assert_preflight_rejected wrong-subject ACCESS_TOKEN="$wrong_subject_token"
assert_preflight_rejected refresh-token ACCESS_TOKEN="$refresh_token"
assert_preflight_rejected bad-signature ACCESS_TOKEN="$bad_signature_token"
assert_preflight_rejected missing-jwt-secret JWT_SECRET=

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_AWS_ACCOUNT_ID=999999999999 "$runner" app-version read 1 1s
if rg -q '^(k6|jfr-|cleanup-(capture|restore))' "$event_log"; then
  printf '%s\n' 'wrong AWS account reached a load or cleanup action' >&2
  exit 1
fi

assert_profile_rejected app-version smoke 2 1 smoke-load-cap
assert_profile_rejected app-version smoke 1 2 smoke-extent-cap
assert_profile_rejected app-version read 41 1s read-rate-cap
assert_profile_rejected app-version read 1 301s read-duration-cap
assert_profile_rejected member-profile-v1 write 11 1s write-rate-cap
assert_profile_rejected member-profile-v1 write 1 121s write-duration-cap
assert_profile_rejected scan-v2-krw external 11 1 external-vus-cap
assert_profile_rejected scan-v2-krw external 1 11 external-iteration-cap
assert_profile_rejected app-version write 1 1s target-profile-mismatch

: >"$event_log"
run_runner env CAMPAIGN_ID=20260831T110000Z "$runner" app-version read 40 300s
grep -q '^k6 phase=measurement .* fixture_offset=4801$' "$event_log"

: >"$event_log"
run_runner env CAMPAIGN_ID=20260831T113000Z "$runner" member-profile-v1 write 10 120s
grep -q '^k6 phase=measurement .* fixture_offset=1201$' "$event_log"
grep -q '^cleanup-capture run=20260831T113000Z-member-profile-v1 target=member-profile-v1 state=snapshot-restore object=none ' "$event_log"
grep -q '^cleanup-restore run=20260831T113000Z-member-profile-v1 target=member-profile-v1 state=snapshot-restore object=none ' "$event_log"

: >"$event_log"
run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
if rg -q '^cleanup-(check|capture|restore)' "$event_log"; then
  printf '%s\n' 'immutable target invoked state cleanup' >&2
  exit 1
fi
report_dir="$artifact_root/$campaign_id/app-version"
test -s "$report_dir/report.html"
test -s "$report_dir/summary.json"
test -s "$report_dir/task-$task_one.jfr"
test -s "$report_dir/task-$task_two.jfr"

event_types="$(sed -n -e '/^k6 /p' -e '/^jfr-start /p' -e '/^jfr-stop /p' -e '/^sleep /p' "$event_log" | sed -E 's/ .*//')"
test "$event_types" = $'k6\nk6\njfr-start\nsleep\nk6\nsleep\njfr-stop'
grep -q '^k6 phase=smoke profile=smoke ' "$event_log"
grep -q '^k6 phase=warmup profile=read .* duration=2m vus=' "$event_log"
grep -q '^k6 phase=measurement profile=read .* duration=1m vus=' "$event_log"
grep -q '^k6 phase=smoke .* fixture_offset=0$' "$event_log"
grep -q '^k6 phase=warmup .* fixture_offset=1$' "$event_log"
grep -q '^k6 phase=measurement .* fixture_offset=601$' "$event_log"
test "$(grep -c '^sleep 10$' "$event_log")" -eq 2
if grep -q 'access_token_arg=true' "$event_log"; then
  printf '%s\n' 'ACCESS_TOKEN was exposed in the k6 argv' >&2
  exit 1
fi

git_sha="$(git -C "$repo_dir" rev-parse HEAD)"
jq -e \
  --arg campaign "$campaign_id" \
  --arg run "$campaign_id-app-version" \
  --arg sha "$git_sha" \
  --arg task_one "$task_one" \
  --arg task_two "$task_two" '
    .campaignId == $campaign and
    .runId == $run and
    .target == "app-version" and
    .baseUrl == "https://dev.kbap.site" and
    .gitSha == $sha and
    .taskDefinition == "kbap-dev-ecs-api:42" and
    .image == "118178010621.dkr.ecr.ap-northeast-2.amazonaws.com/kbap-api:profile-abc123" and
    .taskIds == [$task_one, $task_two] and
    (.startedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.finishedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    .jfrEnabled == true
    and .profile == "read"
    and .load == 5
    and .extent == "1m"
    and .requestMultiplier == 1
    and .targetIterations == 901
    and .measurementIterations == 300
    and .maxHttpRequests == 901
    and .maxBillableProviderCalls == 0
    and .providerCostCap == 0
    and .providerQuota == 0
    and .stateCapability == "none"
    and .objectCleanup == "none"
    and .cleanupSucceeded == true
    and .fixtureOffsets == {smoke: 0, warmup: 1, measurement: 601}
  ' "$report_dir/manifest.json" >/dev/null
if rg -q 'access-super-secret|jwt-super-secret' "$report_dir/manifest.json"; then
  printf '%s\n' 'manifest leaked a secret' >&2
  exit 1
fi
for artifact in "$report_dir/console.log" "$report_dir/report.html" "$report_dir/summary.json" "$report_dir/manifest.json"; do
  if rg -q 'access-super-secret|jwt-super-secret' "$artifact"; then
    printf 'generated artifact leaked a secret: %s\n' "$artifact" >&2
    exit 1
  fi
done

run_runner env CAMPAIGN_ID="$campaign_id" "$runner" home-auth read 10 30s
home_manifest="$artifact_root/$campaign_id/home-auth/manifest.json"
test -s "$home_manifest"
jq -e --arg campaign "$campaign_id" --arg run "$campaign_id-home-auth" \
  '.campaignId == $campaign and .runId == $run' "$home_manifest" >/dev/null
grep '^k6 .*target=home-auth ' "$event_log" | grep -vq "run=$campaign_id-home-auth" && exit 1

auto_root="$temp_dir/auto-artifacts"
: >"$event_log"
PATH="$fake_bin:$PATH" FAKE_EVENT_LOG="$event_log" PERFORMANCE_ARTIFACT_ROOT="$auto_root" \
  FIXTURE_PATH="$fixture" JFR_START_SCRIPT="$fake_bin/jfr-start.sh" JFR_STOP_SCRIPT="$fake_bin/jfr-stop.sh" \
  FIXTURE_CLEANUP_SCRIPT="$fake_bin/cleanup-fixtures.sh" TEST_MODE=true ACCESS_TOKEN="$access_token" \
  JWT_SECRET="$jwt_secret" env -u CAMPAIGN_ID JFR_ENABLED=false "$runner" app-version smoke 1 1
find "$auto_root" -mindepth 2 -maxdepth 2 -type d -path '*/app-version' | grep -Eq '/[0-9]{8}T[0-9]{6}Z/app-version$'
auto_report_dir="$(find "$auto_root" -mindepth 2 -maxdepth 2 -type d -path '*/app-version')"
auto_campaign_id="$(basename "$(dirname "$auto_report_dir")")"
jq -e --arg campaign "$auto_campaign_id" --arg run "$auto_campaign_id-app-version" \
  '.campaignId == $campaign and .runId == $run' "$auto_report_dir/manifest.json" >/dev/null
grep '^k6 .*target=app-version ' "$event_log" | grep -vq "run=$auto_campaign_id-app-version" && exit 1
if grep -q '^jfr-' "$event_log"; then
  printf '%s\n' 'JFR ran during an explicit smoke overhead run' >&2
  exit 1
fi

: >"$event_log"
set +e
FAKE_K6_MAIN_EXIT=23 FAKE_JFR_STOP_FAIL_TASK="$task_one" run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
main_status=$?
set -e
test "$main_status" -eq 23
grep -q '^k6 phase=measurement ' "$event_log"
grep -q '^jfr-stop ' "$event_log"
grep -q "^jfr-stop-task $task_one$" "$event_log"
grep -q "^jfr-stop-task $task_two$" "$event_log"
test ! -e "$artifact_root/$campaign_id/app-version/task-$task_one.jfr"
test -s "$artifact_root/$campaign_id/app-version/task-$task_two.jfr"

: >"$event_log"
set +e
FAKE_JFR_STOP_FAIL_TASK="$task_one" run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
stop_status=$?
set -e
test "$stop_status" -eq 31
grep -q "^jfr-stop-task $task_two$" "$event_log"

: >"$event_log"
set +e
FAKE_REQUIRE_TASK_IDS=1 run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
bound_task_status=$?
set -e
if [[ "$bound_task_status" -ne 0 ]]; then
  printf 'runner did not bind JFR to its exact task snapshot: exit=%s\n' "$bound_task_status" >&2
  exit 1
fi
grep -q "^jfr-start $campaign_id-app-version task_one=$task_one task_two=$task_two$" "$event_log"
grep -q "^jfr-stop $campaign_id-app-version task_one=$task_one task_two=$task_two$" "$event_log"
jq -e --arg one "$task_one" --arg two "$task_two" '.taskIds == [$one, $two]' \
  "$artifact_root/$campaign_id/app-version/manifest.json" >/dev/null

: >"$event_log"
set +e
FAKE_K6_MAIN_EXIT=23 FAKE_FAIL_POST_DELAY=1 run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
main_with_delay_status=$?
set -e
if [[ "$main_with_delay_status" -ne 23 ]]; then
  printf 'main exit 23 was replaced by failed post-delay exit: %s\n' "$main_with_delay_status" >&2
  exit 1
fi
grep -q '^jfr-stop ' "$event_log"

: >"$event_log"
set +e
FAKE_FAIL_POST_DELAY=1 run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
delay_status=$?
set -e
if [[ "$delay_status" -ne 29 ]]; then
  printf 'successful main did not return failed post-delay exit 29: %s\n' "$delay_status" >&2
  exit 1
fi
grep -q '^jfr-stop ' "$event_log"

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_JFR_START_EXIT=17 "$runner" app-version read 5 1m
if grep -q '^k6 phase=measurement ' "$event_log"; then
  printf '%s\n' 'measurement ran after JFR start failure' >&2
  exit 1
fi
grep -q '^jfr-stop ' "$event_log"

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_TASK_COUNT=1 "$runner" app-version read 5 1m
if grep -q '^k6 ' "$event_log"; then
  printf '%s\n' 'k6 ran without exactly two tasks' >&2
  exit 1
fi
: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_TASK_COUNT=3 "$runner" app-version read 5 1m
if grep -q '^k6 ' "$event_log"; then
  printf '%s\n' 'k6 ran with more than two tasks' >&2
  exit 1
fi
: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_RUNNING_COUNT=1 "$runner" app-version read 5 1m
if grep -q '^k6 ' "$event_log"; then
  printf '%s\n' 'k6 ran before ECS reached steady state' >&2
  exit 1
fi
: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_INVALID_TASK_ID=1 "$runner" app-version read 5 1m
if grep -q '^k6 ' "$event_log"; then
  printf '%s\n' 'k6 ran before the runner validated its task snapshot' >&2
  exit 1
fi
assert_nonzero env CAMPAIGN_ID="$campaign_id" JFR_ENABLED=false "$runner" app-version read 5 1m

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_CLEANUP_CHECK_EXIT=46 \
  "$runner" review-create write 1 1s
grep -q '^cleanup-check$' "$event_log"
if rg -q '^(aws|k6|jfr-|cleanup-(capture|restore))' "$event_log"; then
  printf '%s\n' 'fixture cleanup preflight failure reached a load action' >&2
  exit 1
fi

fixture_campaign=20260831T150000Z
: >"$event_log"
run_runner env CAMPAIGN_ID="$fixture_campaign" "$runner" review-create write 2 2s
grep -q '^cleanup-check$' "$event_log"
grep -q '^cleanup-capture run=20260831T150000Z-review-create target=review-create state=tagged-cleanup object=none ' "$event_log"
grep -q '^cleanup-restore run=20260831T150000Z-review-create target=review-create state=tagged-cleanup object=none ' "$event_log"
grep -q '^k6 phase=measurement .* fixture_offset=241$' "$event_log"
test "$(rg -n '^cleanup-capture ' "$event_log" | cut -d: -f1)" -lt "$(rg -n '^k6 phase=smoke ' "$event_log" | cut -d: -f1)"

: >"$event_log"
set +e
FAKE_K6_MAIN_EXIT=23 run_runner env CAMPAIGN_ID="$fixture_campaign" "$runner" review-create write 1 1s
fixture_main_status=$?
set -e
test "$fixture_main_status" -eq 23
grep -q '^cleanup-restore run=20260831T150000Z-review-create ' "$event_log"

: >"$event_log"
set +e
FAKE_CLEANUP_RESTORE_EXIT=47 run_runner env CAMPAIGN_ID="$fixture_campaign" "$runner" review-create write 1 1s
fixture_cleanup_status=$?
set -e
test "$fixture_cleanup_status" -eq 47
jq -e '.stateCapability == "tagged-cleanup" and .objectCleanup == "none" and .cleanupSucceeded == false' \
  "$artifact_root/$fixture_campaign/review-create/manifest.json" >/dev/null

: >"$event_log"
set +e
FAKE_CANCEL_MEASUREMENT=1 run_runner env CAMPAIGN_ID="$fixture_campaign" "$runner" review-create write 1 1s
fixture_cancel_status=$?
set -e
test "$fixture_cancel_status" -eq 143
grep -q '^jfr-stop ' "$event_log"
grep -q '^cleanup-restore run=20260831T150000Z-review-create ' "$event_log"

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_K6_SMOKE_EXIT=41 "$runner" app-version read 5 1m
test "$(grep -c '^k6 ' "$event_log")" -eq 1
grep -q '^k6 phase=smoke ' "$event_log"
if grep -q '^jfr-' "$event_log"; then
  printf '%s\n' 'JFR ran after smoke failure' >&2
  exit 1
fi

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_K6_WARMUP_EXIT=42 "$runner" app-version read 5 1m
test "$(grep -c '^k6 ' "$event_log")" -eq 2
if grep -q '^k6 phase=measurement \|^jfr-' "$event_log"; then
  printf '%s\n' 'measurement or JFR ran after warm-up failure' >&2
  exit 1
fi

manifest_failure_campaign=20260831T130000Z
: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$manifest_failure_campaign" JFR_ENABLED=false FAKE_MANIFEST_WRITE_FAILURE=1 \
  "$runner" app-version smoke 1 1
manifest_failure_dir="$artifact_root/$manifest_failure_campaign/app-version"
chmod u+w "$manifest_failure_dir"
if [[ -e "$manifest_failure_dir/manifest.json" ]]; then
  printf '%s\n' 'manifest write failure left a stale complete manifest' >&2
  exit 1
fi

manifest_and_main_failure_campaign=20260831T140000Z
: >"$event_log"
set +e
run_runner env CAMPAIGN_ID="$manifest_and_main_failure_campaign" JFR_ENABLED=false \
  FAKE_K6_MAIN_EXIT=23 FAKE_MANIFEST_WRITE_FAILURE=1 "$runner" app-version smoke 1 1 \
  >"$temp_dir/manifest-main.stdout" 2>"$temp_dir/manifest-main.stderr"
manifest_and_main_status=$?
set -e
manifest_and_main_dir="$artifact_root/$manifest_and_main_failure_campaign/app-version"
chmod u+w "$manifest_and_main_dir"
if [[ "$manifest_and_main_status" -ne 23 ]]; then
  printf 'main exit 23 was replaced by manifest write failure: %s\n' "$manifest_and_main_status" >&2
  exit 1
fi

: >"$event_log"
run_runner env CAMPAIGN_ID="$campaign_id" "$runner" scan-v2-krw external 10 10
grep -q '^cleanup-capture run=20260831T120000Z-scan-v2-krw target=scan-v2-krw state=scan-cleanup object=scanGeneratedFoodImageRefs ' "$event_log"
grep -q '^cleanup-restore run=20260831T120000Z-scan-v2-krw target=scan-v2-krw state=scan-cleanup object=scanGeneratedFoodImageRefs ' "$event_log"
grep -q '^k6 phase=smoke profile=smoke target=scan-v2-krw .* vus= iterations= ' "$event_log"
if ! grep -q '^k6 phase=warmup profile=external target=scan-v2-krw .* vus=1 iterations=1 max_duration=2m access_token_arg=false fixture_offset=1$' "$event_log"; then
  printf '%s\n' 'external warm-up duplicated the requested measurement instead of using the bounded 1x1 phase' >&2
  exit 1
fi
grep -q '^k6 phase=measurement profile=external target=scan-v2-krw .* vus=10 iterations=10 ' "$event_log"
grep -q '^k6 phase=measurement .* fixture_offset=2$' "$event_log"
jq -e '
  .requestMultiplier == 2 and .targetIterations == 102 and .measurementIterations == 100 and .maxHttpRequests == 204 and
  .billableRequests == 204 and .maxBillableProviderCalls == 204 and .providerCostCap == 204 and .providerQuota == 200 and
  .stateCapability == "scan-cleanup" and .objectCleanup == "scanGeneratedFoodImageRefs" and .cleanupSucceeded == true and
  .fixtureOffsets == {smoke: 0, warmup: 1, measurement: 2}
' "$artifact_root/$campaign_id/scan-v2-krw/manifest.json" >/dev/null

: >"$event_log"
run_runner env CAMPAIGN_ID=20260831T170000Z "$runner" order-create-location external 1 1
grep -q '^cleanup-capture run=20260831T170000Z-order-create-location target=order-create-location state=tagged-cleanup object=none ' "$event_log"
grep -q '^cleanup-restore run=20260831T170000Z-order-create-location target=order-create-location state=tagged-cleanup object=none ' "$event_log"

: >"$event_log"
run_runner env CAMPAIGN_ID=20260831T180000Z "$runner" image-complete write 1 1s
grep -q '^cleanup-capture run=20260831T180000Z-image-complete target=image-complete state=tagged-cleanup object=imageCompleteFixtures ' "$event_log"
grep -q '^cleanup-restore run=20260831T180000Z-image-complete target=image-complete state=tagged-cleanup object=imageCompleteFixtures ' "$event_log"

: >"$event_log"
assert_nonzero env CAMPAIGN_ID=20260831T190000Z STORAGE_BUCKET=unexpected-bucket \
  "$runner" image-complete write 1 1s
if rg -q '^(k6|cleanup-(capture|restore))' "$event_log"; then
  printf '%s\n' 'mismatched storage bucket reached load or cleanup' >&2
  exit 1
fi

: >"$event_log"
set +e
run_runner env CAMPAIGN_ID="$campaign_id" "$runner" scan-v2-krw external 20 10 \
  >"$temp_dir/external-cap.stdout" 2>"$temp_dir/external-cap.stderr"
external_cap_status=$?
set -e
if [[ "$external_cap_status" -eq 0 ]] || grep -q '^k6 ' "$event_log"; then
  printf 'external projected budget over 200 was not rejected before k6: exit=%s calls=%s\n' \
    "$external_cap_status" "$(grep -c '^k6 ' "$event_log" || true)" >&2
  exit 1
fi
grep -q 'external VUS and ITERATIONS must be integers from 1 to 10' "$temp_dir/external-cap.stderr"

assert_external_input_rejected 18446744073709551616 1 external-overflow
assert_external_input_rejected 0 1 external-zero
assert_external_input_rejected 01 1 external-leading-zero
assert_external_input_rejected 1x 1 external-nondigit
assert_external_input_rejected 1 0 external-zero-iterations

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" "$runner" scan-v2-krw read 5 1m
if grep -q '^k6 ' "$event_log"; then
  printf '%s\n' 'external target safety relied on the caller PROFILE and ran smoke first' >&2
  exit 1
fi

sensitive_campaign=20260831T160000Z
: >"$event_log"
FAKE_EMIT_SENSITIVE_OUTPUT=1 run_runner env CAMPAIGN_ID="$sensitive_campaign" \
  AWS_SECRET_ACCESS_KEY=aws-env-secret MYSQL_PWD=mysql-env-secret \
  "$runner" app-version read 1 1s >"$temp_dir/sensitive.stdout" 2>"$temp_dir/sensitive.stderr"
sensitive_report="$artifact_root/$sensitive_campaign/app-version"
jq -e . "$sensitive_report/summary.json" >/dev/null
for output in "$temp_dir/sensitive.stdout" "$sensitive_report/console.log"; do
  rg -q '^phase=(smoke|warmup|jfr-start|measurement|jfr-stop) ' "$output"
  rg -q '\[REDACTED' "$output"
done
for output in "$temp_dir/sensitive.stdout" "$sensitive_report/console.log" "$sensitive_report/report.html" "$sensitive_report/summary.json"; do
  if rg -Fq "$access_token" "$output" || rg -Fq "$jwt_secret" "$output" || \
    rg -q 'aws-(log|env)-secret|provider-log-secret|json-log-secret|summary-aws-secret|mysql-env-secret|s3://|https?://' "$output"; then
    printf 'sanitized output leaked a secret or storage/HTTP URL: %s\n' "$output" >&2
    exit 1
  fi
done

while IFS= read -r artifact; do
  if rg -q 'access-super-secret|jwt-super-secret|s3://' "$artifact" || \
    rg -Fq "$access_token" "$artifact" || rg -Fq "$jwt_secret" "$artifact"; then
    printf 'generated artifact leaked a secret: %s\n' "$artifact" >&2
    exit 1
  fi
done < <(find "$artifact_root" "$auto_root" -type f \( -name console.log -o -name report.html -o -name summary.json -o -name manifest.json \))

if "$real_k6" inspect -e ACCESS_TOKEN=test -e SCAN_IMAGE_PATH=seed.jpg "$repo_dir/k6/scan-burst.js" >"$temp_dir/scan-missing-run.out" 2>&1; then
  printf '%s\n' 'scan burst accepted a missing RUN_ID' >&2
  exit 1
fi
grep -q 'RUN_ID required' "$temp_dir/scan-missing-run.out"

python3 "$repo_dir/k6/tests/mock-server.py" >"$temp_dir/mock-server.log" 2>&1 &
mock_pid=$!
for _ in {1..50}; do
  curl --silent --fail http://127.0.0.1:18081/health >/dev/null && break
  sleep 0.1
done
mkdir -p "$temp_dir/env-token-report"
env ACCESS_TOKEN=test "$real_k6" run --quiet \
  -e TARGET=home-auth \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e RUN_ID=env-token-contract \
  -e REPORT_DIR="$temp_dir/env-token-report" \
  -e PROFILE=smoke \
  "$repo_dir/k6/endpoint.js" >/dev/null
test -s "$temp_dir/env-token-report/summary.json"
"$real_k6" run --quiet --out "json=$temp_dir/scan-metrics.json" \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e ACCESS_TOKEN=test \
  -e SCAN_IMAGE_PATH=seed.jpg \
  -e RUN_ID=scan-contract \
  -e PHASE=measurement \
  -e VUS=1 \
  "$repo_dir/k6/scan-burst.js" >/dev/null
kill "$mock_pid" 2>/dev/null || true
wait "$mock_pid" 2>/dev/null || true
mock_pid=""
jq -s -e '
  [.[] | select(.type == "Point" and .metric == "http_reqs") | .data.tags] as $tags |
  ($tags | length) == 2 and
  all($tags[];
    .run_id == "scan-contract" and
    .target == "scan-v2-krw" and
    .route == "/api/scans" and
    .phase == "measurement")
' "$temp_dir/scan-metrics.json" >/dev/null

printf '%s\n' 'endpoint runner: PASS'
