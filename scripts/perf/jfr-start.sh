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

TASK_OUTPUT=$(resolve_task_ids "$@")
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

for task_id in "${TASK_IDS[@]}"; do
  if ! output=$(execute_in_task "$task_id" "jcmd 1 JFR.start name=$RUN_ID settings=/app/kbap-profile.jfc filename=/tmp/$RUN_ID.jfr maxsize=256m"); then
    rollback_started_recordings
    exit 1
  fi
  if [[ "$output" != *"Started recording"* ]]; then
    echo "error: JFR did not start on task $task_id" >&2
    rollback_started_recordings
    exit 1
  fi
  STARTED_TASK_IDS+=("$task_id")
done
