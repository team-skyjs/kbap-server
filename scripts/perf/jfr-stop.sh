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

mkdir -p "$REPORT_DIR"
TASK_OUTPUT=$(resolve_task_ids "$@")
TASK_IDS=()
while IFS= read -r task_id; do
  [[ -n "$task_id" ]] && TASK_IDS+=("$task_id")
done <<<"$TASK_OUTPUT"

for task_id in "${TASK_IDS[@]}"; do
  execute_in_task "$task_id" "jcmd 1 JFR.stop name=$RUN_ID"
  execute_in_task "$task_id" "jfr summary /tmp/$RUN_ID.jfr"
  execute_in_task "$task_id" "aws s3 cp /tmp/$RUN_ID.jfr s3://$PERF_ARTIFACT_BUCKET/$RUN_ID/task-$task_id.jfr --sse AES256"
  execute_in_task "$task_id" "rm -f /tmp/$RUN_ID.jfr"
done

for task_id in "${TASK_IDS[@]}"; do
  local_file="$REPORT_DIR/task-$task_id.jfr"
  aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
    s3 cp "s3://$PERF_ARTIFACT_BUCKET/$RUN_ID/task-$task_id.jfr" "$local_file"
  test -s "$local_file"
  summarize_jfr "$local_file"
done
