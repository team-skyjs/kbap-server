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

for task_id in "${TASK_IDS[@]}"; do
  STARTED_TASK_IDS+=("$task_id")
  if ! output=$(execute_in_task "$task_id" "jcmd 1 JFR.start name=$RUN_ID settings=/app/kbap-profile.jfc filename=/tmp/$RUN_ID.jfr maxsize=256m"); then
    exit 1
  fi
  if [[ "$output" != *"Started recording"* ]]; then
    echo "error: JFR did not start on task $task_id" >&2
    exit 1
  fi
  printf '%s\n' "$output"
done
start_complete=true
trap - EXIT INT TERM
