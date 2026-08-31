#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

if [[ $# -ne 1 && $# -ne 3 ]]; then
  echo "usage: $0 RUN_ID [TASK_ID TASK_ID]" >&2
  exit 2
fi

RUN_ID=$1
shift
if [[ ! "$RUN_ID" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "error: RUN_ID must match ^[a-zA-Z0-9._-]+$" >&2
  exit 2
fi

validate_dev_aws_environment
require_commands aws session-manager-plugin
[[ $# -eq 0 ]] && validate_dev_aws_account

TASK_OUTPUT=$(resolve_task_ids "$@")
[[ $# -ne 0 ]] && validate_dev_aws_account
TASK_IDS=()
while IFS= read -r task_id; do
  [[ -n "$task_id" ]] && TASK_IDS+=("$task_id")
done <<<"$TASK_OUTPUT"

STARTED_TASK_IDS=()
rollback_started_recordings() {
  local task_id
  for task_id in "${STARTED_TASK_IDS[@]}"; do
    if ! execute_in_task "$task_id" "jcmd 1 JFR.stop name=$RUN_ID"; then
      echo "warning: failed to stop JFR recording on task $task_id" >&2
    fi
  done
}

start_complete=false
rollback_on_exit() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$start_complete" != "true" ]]; then
    set +e
    rollback_started_recordings
  fi
  exit "$status"
}
trap rollback_on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ECS exec 세션은 스트림 뒷부분을 곧잘 자른다(2026-08-31 실측: 첫 stdout 줄만 안정 전달).
# 판정을 스트림 전문 검사 대신 컨테이너 안 파일 grep + 단일 마커(JFR_START_OK) 첫 줄로 옮기고,
# 마커 유실 대비 재시도한다(재시작 전 같은 이름 녹화를 정지해 중복 이름 오류 방지).
for task_id in "${TASK_IDS[@]}"; do
  STARTED_TASK_IDS+=("$task_id")
  started=false
  for attempt in 1 2 3; do
    start_command="sh -c 'jcmd 1 JFR.stop name=$RUN_ID >/dev/null 2>&1; jcmd 1 JFR.start name=$RUN_ID settings=/app/kbap-profile.jfc filename=/tmp/$RUN_ID.jfr maxsize=256m >/tmp/$RUN_ID.start.log 2>&1; if grep -q \"Started recording\" /tmp/$RUN_ID.start.log; then echo JFR_START_OK; else cat /tmp/$RUN_ID.start.log >&2; fi; rm -f /tmp/$RUN_ID.start.log"
    start_command+="'"
    if output=$(execute_in_task "$task_id" "$start_command") && [[ "$output" == *"JFR_START_OK"* ]]; then
      started=true
      printf 'task %s: JFR recording started (attempt %s/3)\n' "$task_id" "$attempt"
      break
    fi
    echo "warning: JFR start attempt $attempt/3 unconfirmed on task $task_id" >&2
  done
  if [[ "$started" != "true" ]]; then
    echo "error: JFR did not start on task $task_id" >&2
    exit 1
  fi
done
start_complete=true
trap - EXIT INT TERM
