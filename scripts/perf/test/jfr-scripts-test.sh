#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
START="$PERF_DIR/jfr-start.sh"
STOP="$PERF_DIR/jfr-stop.sh"
RUN_ID="load-run_20260831.1"
TASK_ONE="11111111111111111111111111111111"
TASK_TWO="22222222222222222222222222222222"
BUCKET="kbap-dev-ecs-performance-artifacts"
TEST_DIR=$(mktemp -d)
FAKE_BIN="$TEST_DIR/bin"
CALLS="$TEST_DIR/aws-calls"

cleanup() {
  rm -rf "$TEST_DIR"
}
trap cleanup EXIT

mkdir -p "$FAKE_BIN"

cat >"$FAKE_BIN/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\t' "$@" >>"$FAKE_AWS_CALLS"
printf '\n' >>"$FAKE_AWS_CALLS"

if [[ " $* " == *" sts get-caller-identity "* ]]; then
  printf '%s\n' "${FAKE_AWS_ACCOUNT_ID:-118178010621}"
  exit 0
fi

if [[ " $* " == *" ecs list-tasks "* ]]; then
  case "${FAKE_TASK_COUNT:-2}" in
    1) printf 'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/11111111111111111111111111111111\n' ;;
    2) printf 'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/11111111111111111111111111111111\narn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/22222222222222222222222222222222\n' ;;
    3) printf 'arn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/11111111111111111111111111111111\narn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/22222222222222222222222222222222\narn:aws:ecs:ap-northeast-2:123456789012:task/kbap-dev-ecs-cluster/33333333333333333333333333333333\n' ;;
  esac
  exit 0
fi

if [[ " $* " == *" ecs execute-command "* ]]; then
  command=''
  for ((index = 1; index <= $#; index++)); do
    if [[ "${!index}" == "--command" ]]; then
      next=$((index + 1))
      command="${!next}"
      break
    fi
  done
  if [[ "$command" == *"JFR.start"* ]]; then
    if [[ "${FAKE_CANCEL_START_TASK:-}" != "" && "$*" == *"${FAKE_CANCEL_START_TASK}"* ]]; then
      kill -TERM "$PPID"
      exit 143
    fi
    if [[ "${FAKE_FAIL_START_TASK:-}" != "" && "$*" == *"${FAKE_FAIL_START_TASK}"* ]]; then
      echo 'start failed' >&2
      exit 1
    fi
    if [[ "${FAKE_MISSING_START_MARKER_TASK:-}" != "" && "$*" == *"${FAKE_MISSING_START_MARKER_TASK}"* ]]; then
      echo 'recording response unavailable'
      exit 0
    fi
    echo 'Started recording 1.'
  fi
  if [[ "$command" == *"JFR.stop"* && -n "${FAKE_FAIL_STOP_TASK:-}" && "$*" == *"${FAKE_FAIL_STOP_TASK}"* ]]; then
    echo 'stop failed' >&2
    exit 41
  fi
  if [[ "$command" == *"jfr summary"* && -n "${FAKE_FAIL_SUMMARY_TASK:-}" && "$*" == *"${FAKE_FAIL_SUMMARY_TASK}"* ]]; then
    echo 'summary failed' >&2
    exit 42
  fi
  if [[ "$command" == *"aws s3 cp"* && -n "${FAKE_FAIL_UPLOAD_TASK:-}" && "$*" == *"${FAKE_FAIL_UPLOAD_TASK}"* ]]; then
    echo 'upload failed' >&2
    exit 43
  fi
  exit 0
fi

if [[ " $* " == *" s3 cp "* ]]; then
  target="${@: -2:1}"
  if [[ -n "${FAKE_FAIL_DOWNLOAD_TASK:-}" && "$*" == *"${FAKE_FAIL_DOWNLOAD_TASK}"* ]] || \
    [[ -n "${FAKE_FAIL_UPLOAD_TASK:-}" && "$*" == *"${FAKE_FAIL_UPLOAD_TASK}"* ]]; then
    echo 'download failed' >&2
    exit 44
  fi
  if [[ "${FAKE_S3_EMPTY:-0}" == "1" ]] || \
    [[ -n "${FAKE_S3_EMPTY_TASK:-}" && "$*" == *"${FAKE_S3_EMPTY_TASK}"* ]]; then
    : >"$target"
  else
    printf 'fake jfr data\n' >"$target"
  fi
  exit 0
fi

echo "unexpected aws invocation: $*" >&2
exit 64
AWS
chmod +x "$FAKE_BIN/aws"

cat >"$FAKE_BIN/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\t' "$@" >>"$FAKE_DOCKER_CALLS"
printf '\n' >>"$FAKE_DOCKER_CALLS"
if [[ -n "${FAKE_FAIL_VALIDATE_TASK:-}" && "$*" == *"${FAKE_FAIL_VALIDATE_TASK}"* ]]; then
  exit 45
fi
DOCKER
chmod +x "$FAKE_BIN/docker"

cat >"$FAKE_BIN/session-manager-plugin" <<'PLUGIN'
#!/usr/bin/env bash
exit 0
PLUGIN
chmod +x "$FAKE_BIN/session-manager-plugin"

run_with_fakes() {
  PATH="$FAKE_BIN:$PATH" \
    FAKE_AWS_CALLS="$CALLS" \
    FAKE_DOCKER_CALLS="$TEST_DIR/docker-calls" \
    "$@"
}

assert_exit() {
  local expected=$1
  shift
  set +e
  run_with_fakes "$@" >"$TEST_DIR/stdout" 2>"$TEST_DIR/stderr"
  local actual=$?
  set -e
  if [[ "$actual" -ne "$expected" ]]; then
    echo "expected exit $expected, got $actual: $*" >&2
    cat "$TEST_DIR/stderr" >&2
    exit 1
  fi
}

assert_nonzero() {
  set +e
  run_with_fakes "$@" >"$TEST_DIR/stdout" 2>"$TEST_DIR/stderr"
  local actual=$?
  set -e
  if [[ "$actual" -eq 0 ]]; then
    echo "expected non-zero exit: $*" >&2
    exit 1
  fi
}

assert_count() {
  local expected=$1
  local pattern=$2
  local actual
  actual=$(rg -c -- "$pattern" "$CALLS" || true)
  if [[ "$actual" -ne "$expected" ]]; then
    echo "expected $expected matches for $pattern, got $actual" >&2
    cat "$CALLS" >&2
    exit 1
  fi
}

assert_task_one_cleanup_failure() {
  local expected=$1
  local failure_env=$2
  local label=$3
  local failure_report="$TEST_DIR/$label-report"
  local actual
  mkdir -p "$failure_report"
  : >"$CALLS"
  set +e
  run_with_fakes env "$failure_env=$TASK_ONE" "$STOP" "$RUN_ID" "$failure_report" "$TASK_ONE" "$TASK_TWO" \
    >"$TEST_DIR/$label.stdout" 2>"$TEST_DIR/$label.stderr"
  actual=$?
  set -e
  if [[ "$actual" -ne "$expected" ]]; then
    echo "expected cleanup exit $expected, got $actual: $label" >&2
    cat "$TEST_DIR/$label.stderr" >&2
    exit 1
  fi
  assert_count 2 "JFR.stop name=$RUN_ID"
  assert_count 2 "jfr\\ summary /tmp/$RUN_ID.jfr"
  assert_count 2 "s3\\ cp /tmp/$RUN_ID.jfr s3://$BUCKET/$RUN_ID/task-"
  test -s "$failure_report/task-$TASK_TWO.jfr"
}

assert_exit 2 "$START" 'invalid/run id'
test ! -s "$CALLS"

: >"$CALLS"
assert_exit 2 env AWS_PROFILE=prod "$START" "$RUN_ID"
test ! -s "$CALLS"

: >"$CALLS"
assert_exit 2 env PERFORMANCE_ARTIFACT_BUCKET=prod-bucket "$STOP" "$RUN_ID" "$TEST_DIR/prod-report"
test ! -s "$CALLS"

: >"$CALLS"
assert_exit 3 env FAKE_TASK_COUNT=1 "$START" "$RUN_ID"
assert_exit 3 env FAKE_TASK_COUNT=3 "$START" "$RUN_ID"

: >"$CALLS"
run_with_fakes "$START" "$RUN_ID"
assert_count 2 'JFR.start'
assert_count 1 "$TASK_ONE"
assert_count 1 "$TASK_TWO"

: >"$CALLS"
run_with_fakes env FAKE_TASK_COUNT=3 "$START" "$RUN_ID" "$TASK_ONE" "$TASK_TWO"
assert_count 0 'ecs[[:space:]]+list-tasks'
assert_count 2 'JFR.start'
assert_count 1 "$TASK_ONE"
assert_count 1 "$TASK_TWO"

: >"$CALLS"
assert_nonzero env FAKE_FAIL_START_TASK="$TASK_TWO" "$START" "$RUN_ID"
assert_count 2 'JFR.start'
assert_count 2 "JFR.stop name=$RUN_ID"

: >"$CALLS"
assert_nonzero env FAKE_MISSING_START_MARKER_TASK="$TASK_ONE" "$START" "$RUN_ID" "$TASK_ONE" "$TASK_TWO"
assert_count 1 'JFR.start'
assert_count 1 "JFR.stop name=$RUN_ID"

: >"$CALLS"
assert_nonzero env FAKE_MISSING_START_MARKER_TASK="$TASK_TWO" "$START" "$RUN_ID" "$TASK_ONE" "$TASK_TWO"
assert_count 2 'JFR.start'
assert_count 2 "JFR.stop name=$RUN_ID"

: >"$CALLS"
assert_nonzero env FAKE_CANCEL_START_TASK="$TASK_TWO" "$START" "$RUN_ID" "$TASK_ONE" "$TASK_TWO"
assert_count 2 'JFR.start'
assert_count 2 "JFR.stop name=$RUN_ID"

REPORT_DIR="$TEST_DIR/report"
mkdir -p "$REPORT_DIR"
: >"$CALLS"
run_with_fakes "$STOP" "$RUN_ID" "$REPORT_DIR"
assert_count 2 "JFR.stop name=$RUN_ID"
assert_count 2 "jfr\\ summary /tmp/$RUN_ID.jfr"
assert_count 2 "s3\\ cp /tmp/$RUN_ID.jfr s3://$BUCKET/$RUN_ID/task-"
assert_count 2 '--sse[[:space:]]+AES256'
assert_count 4 '--only-show-errors'
test -s "$REPORT_DIR/task-$TASK_ONE.jfr"
test -s "$REPORT_DIR/task-$TASK_TWO.jfr"

EXPLICIT_REPORT_DIR="$TEST_DIR/explicit-report"
mkdir -p "$EXPLICIT_REPORT_DIR"
: >"$CALLS"
run_with_fakes env FAKE_TASK_COUNT=3 "$STOP" "$RUN_ID" "$EXPLICIT_REPORT_DIR" "$TASK_ONE" "$TASK_TWO"
assert_count 0 'ecs[[:space:]]+list-tasks'
assert_count 2 "JFR.stop name=$RUN_ID"
assert_count 5 "$TASK_ONE"
assert_count 5 "$TASK_TWO"
test -s "$EXPLICIT_REPORT_DIR/task-$TASK_ONE.jfr"
test -s "$EXPLICIT_REPORT_DIR/task-$TASK_TWO.jfr"

STOP_FAILURE_REPORT_DIR="$TEST_DIR/stop-failure-report"
mkdir -p "$STOP_FAILURE_REPORT_DIR"
: >"$CALLS"
assert_exit 41 env FAKE_FAIL_STOP_TASK="$TASK_ONE" "$STOP" "$RUN_ID" "$STOP_FAILURE_REPORT_DIR" "$TASK_ONE" "$TASK_TWO"
assert_count 2 "JFR.stop name=$RUN_ID"
assert_count 2 "jfr\\ summary /tmp/$RUN_ID.jfr"
test -s "$STOP_FAILURE_REPORT_DIR/task-$TASK_ONE.jfr"
test -s "$STOP_FAILURE_REPORT_DIR/task-$TASK_TWO.jfr"

assert_task_one_cleanup_failure 42 FAKE_FAIL_SUMMARY_TASK summary-failure
assert_task_one_cleanup_failure 43 FAKE_FAIL_UPLOAD_TASK upload-failure
assert_count 1 "rm -f /tmp/$RUN_ID.jfr"
assert_task_one_cleanup_failure 44 FAKE_FAIL_DOWNLOAD_TASK download-failure
assert_task_one_cleanup_failure 1 FAKE_S3_EMPTY_TASK empty-failure
test ! -e "$TEST_DIR/empty-failure-report/task-$TASK_ONE.jfr"
assert_task_one_cleanup_failure 45 FAKE_FAIL_VALIDATE_TASK validation-failure

: >"$CALLS"
assert_exit 2 "$START" "$RUN_ID" 'invalid/task' "$TASK_TWO"
test ! -s "$CALLS"

: >"$CALLS"
assert_exit 2 "$START" "$RUN_ID" "$TASK_ONE" "$TASK_ONE"
test ! -s "$CALLS"

EMPTY_REPORT_DIR="$TEST_DIR/empty-report"
mkdir -p "$EMPTY_REPORT_DIR"
assert_nonzero env FAKE_S3_EMPTY=1 "$STOP" "$RUN_ID" "$EMPTY_REPORT_DIR"

echo "JFR ECS scripts: PASS"
