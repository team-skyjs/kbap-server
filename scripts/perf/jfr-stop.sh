#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

if [[ $# -ne 2 && $# -ne 4 ]]; then
  echo "usage: $0 RUN_ID REPORT_DIR [TASK_ID TASK_ID]" >&2
  exit 2
fi

RUN_ID=$1
REPORT_DIR=$2
shift 2
if [[ ! "$RUN_ID" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "error: RUN_ID must match ^[a-zA-Z0-9._-]+$" >&2
  exit 2
fi

validate_dev_aws_environment
require_commands aws session-manager-plugin docker
[[ $# -eq 0 ]] && validate_dev_aws_account

mkdir -p "$REPORT_DIR"
TASK_OUTPUT=$(resolve_task_ids "$@")
[[ $# -ne 0 ]] && validate_dev_aws_account
TASK_IDS=()
while IFS= read -r task_id; do
  [[ -n "$task_id" ]] && TASK_IDS+=("$task_id")
done <<<"$TASK_OUTPUT"

FAILURE_STATUS=0
record_failure() {
  local status=$1
  if [[ "$FAILURE_STATUS" -eq 0 && "$status" -ne 0 ]]; then
    FAILURE_STATUS=$status
  fi
}

attempt() {
  local status=0
  "$@" || status=$?
  record_failure "$status"
}

for task_id in "${TASK_IDS[@]}"; do
  attempt execute_in_task "$task_id" "jcmd 1 JFR.stop name=$RUN_ID"
  attempt execute_in_task "$task_id" "jfr summary /tmp/$RUN_ID.jfr"
  upload_status=0
  execute_in_task "$task_id" "aws s3 cp /tmp/$RUN_ID.jfr s3://$PERF_ARTIFACT_BUCKET/$RUN_ID/task-$task_id.jfr --sse AES256 --only-show-errors" || upload_status=$?
  record_failure "$upload_status"
  if [[ "$upload_status" -eq 0 ]]; then
    attempt execute_in_task "$task_id" "rm -f /tmp/$RUN_ID.jfr"
  fi
done

for task_id in "${TASK_IDS[@]}"; do
  local_file="$REPORT_DIR/task-$task_id.jfr"
  rm_status=0
  rm -f "$local_file" || rm_status=$?
  record_failure "$rm_status"
  download_status=0
  aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    s3 cp "s3://$PERF_ARTIFACT_BUCKET/$RUN_ID/task-$task_id.jfr" "$local_file" --only-show-errors || download_status=$?
  record_failure "$download_status"
  if [[ "$download_status" -eq 0 ]]; then
    if [[ -s "$local_file" ]]; then
      attempt summarize_jfr "$local_file"
    else
      record_failure 1
      attempt rm -f "$local_file"
    fi
  fi
done

exit "$FAILURE_STATUS"
