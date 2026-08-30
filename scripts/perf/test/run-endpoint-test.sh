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

cat >"$fake_bin/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >>"$FAKE_EVENT_LOG"
if [[ " $* " == *" ecs wait services-stable "* ]]; then
  exit 0
fi
if [[ " $* " == *" ecs describe-services "* ]]; then
  printf '%s\t%s\t%s\t%s\n' "${FAKE_DESIRED_COUNT:-2}" "${FAKE_RUNNING_COUNT:-2}" "${FAKE_PENDING_COUNT:-0}" 'arn:aws:ecs:ap-northeast-2:123456789012:task-definition/kbap-dev-ecs-api:42'
  exit 0
fi
if [[ " $* " == *" ecs list-tasks "* ]]; then
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
  printf '%s\n' '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/kbap-api:profile-abc123'
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
    esac
    shift 2
    continue
  fi
  shift
done
printf 'k6 phase=%s profile=%s target=%s run=%s duration=%s\n' "$phase" "$profile" "$target" "$run_id" "$duration" >>"$FAKE_EVENT_LOG"
mkdir -p "$report_dir"
printf '<html>fake</html>\n' >"$report_dir/report.html"
printf '{"fake":true}\n' >"$report_dir/summary.json"
if [[ "$phase" == "measurement" ]]; then
  exit "${FAKE_K6_MAIN_EXIT:-0}"
fi
K6

cat >"$fake_bin/jfr-start.sh" <<'JFR_START'
#!/usr/bin/env bash
set -euo pipefail
printf 'jfr-start %s tasks=2\n' "$1" >>"$FAKE_EVENT_LOG"
exit "${FAKE_JFR_START_EXIT:-0}"
JFR_START

cat >"$fake_bin/jfr-stop.sh" <<'JFR_STOP'
#!/usr/bin/env bash
set -euo pipefail
printf 'jfr-stop %s\n' "$1" >>"$FAKE_EVENT_LOG"
mkdir -p "$2"
printf 'fake jfr\n' >"$2/task-11111111111111111111111111111111.jfr"
printf 'fake jfr\n' >"$2/task-22222222222222222222222222222222.jfr"
exit "${FAKE_JFR_STOP_EXIT:-0}"
JFR_STOP

cat >"$fake_bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
set -euo pipefail
printf 'sleep %s\n' "$1" >>"$FAKE_EVENT_LOG"
SLEEP

chmod +x "$fake_bin/aws" "$fake_bin/k6" "$fake_bin/jfr-start.sh" "$fake_bin/jfr-stop.sh" "$fake_bin/sleep"

run_runner() {
  PATH="$fake_bin:$PATH" \
    FAKE_EVENT_LOG="$event_log" \
    PERFORMANCE_ARTIFACT_ROOT="$artifact_root" \
    FIXTURE_PATH="$fixture" \
    JFR_START_SCRIPT="$fake_bin/jfr-start.sh" \
    JFR_STOP_SCRIPT="$fake_bin/jfr-stop.sh" \
    ACCESS_TOKEN='access-super-secret' \
    JWT_SECRET='jwt-super-secret' \
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

: >"$event_log"
run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
report_dir="$artifact_root/$campaign_id/app-version"
test -s "$report_dir/report.html"
test -s "$report_dir/summary.json"
test -s "$report_dir/task-$task_one.jfr"
test -s "$report_dir/task-$task_two.jfr"

event_types="$(sed -n -e '/^k6 /p' -e '/^jfr-/p' -e '/^sleep /p' "$event_log" | sed -E 's/ .*//')"
test "$event_types" = $'k6\nk6\njfr-start\nsleep\nk6\nsleep\njfr-stop'
grep -q '^k6 phase=smoke profile=smoke ' "$event_log"
grep -q '^k6 phase=warmup profile=read .* duration=2m$' "$event_log"
grep -q '^k6 phase=measurement profile=read .* duration=1m$' "$event_log"
test "$(grep -c '^sleep 10$' "$event_log")" -eq 2

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
    .image == "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/kbap-api:profile-abc123" and
    .taskIds == [$task_one, $task_two] and
    (.startedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.finishedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    .jfrEnabled == true
  ' "$report_dir/manifest.json" >/dev/null
if rg -q 'access-super-secret|jwt-super-secret' "$report_dir/manifest.json"; then
  printf '%s\n' 'manifest leaked a secret' >&2
  exit 1
fi

run_runner env CAMPAIGN_ID="$campaign_id" "$runner" home-auth read 10 30s
test -s "$artifact_root/$campaign_id/home-auth/manifest.json"

auto_root="$temp_dir/auto-artifacts"
: >"$event_log"
PATH="$fake_bin:$PATH" FAKE_EVENT_LOG="$event_log" PERFORMANCE_ARTIFACT_ROOT="$auto_root" \
  FIXTURE_PATH="$fixture" JFR_START_SCRIPT="$fake_bin/jfr-start.sh" JFR_STOP_SCRIPT="$fake_bin/jfr-stop.sh" \
  ACCESS_TOKEN=test env -u CAMPAIGN_ID JFR_ENABLED=false "$runner" app-version smoke 1 1
find "$auto_root" -mindepth 2 -maxdepth 2 -type d -path '*/app-version' | grep -Eq '/[0-9]{8}T[0-9]{6}Z/app-version$'
if grep -q '^jfr-' "$event_log"; then
  printf '%s\n' 'JFR ran during an explicit smoke overhead run' >&2
  exit 1
fi

: >"$event_log"
set +e
FAKE_K6_MAIN_EXIT=23 FAKE_JFR_STOP_EXIT=31 run_runner env CAMPAIGN_ID="$campaign_id" "$runner" app-version read 5 1m
main_status=$?
set -e
test "$main_status" -eq 23
grep -q '^k6 phase=measurement ' "$event_log"
grep -q '^jfr-stop ' "$event_log"

: >"$event_log"
assert_nonzero env CAMPAIGN_ID="$campaign_id" FAKE_JFR_START_EXIT=17 "$runner" app-version read 5 1m
if grep -q '^k6 phase=measurement ' "$event_log"; then
  printf '%s\n' 'measurement ran after JFR start failure' >&2
  exit 1
fi

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
assert_nonzero env CAMPAIGN_ID="$campaign_id" JFR_ENABLED=false "$runner" app-version read 5 1m

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
"$real_k6" run --quiet --out "json=$temp_dir/scan-metrics.json" \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e ACCESS_TOKEN=test \
  -e SCAN_IMAGE_PATH=seed.jpg \
  -e RUN_ID=scan-contract \
  -e PHASE=measurement \
  -e VUS=1 \
  "$repo_dir/k6/scan-burst.js" >/dev/null
kill "$mock_pid"
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
