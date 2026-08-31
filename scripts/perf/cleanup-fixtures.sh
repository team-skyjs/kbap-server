#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
capture_sql="$repo_dir/k6/scripts/capture-fixtures.sql"
cleanup_sql="$repo_dir/k6/scripts/cleanup-fixtures.sql"
source "$script_dir/lib.sh"

validate_capabilities() {
  case "$1" in
    snapshot-restore | tagged-cleanup | scan-cleanup) ;;
    *) echo "error: unsupported state cleanup capability" >&2; return 2 ;;
  esac
  case "$2" in
    none | imageCompleteFixtures | scanGeneratedFoodImageRefs) ;;
    *) echo "error: unsupported object cleanup capability" >&2; return 2 ;;
  esac
}

validate_database() {
  local name
  for name in MYSQL_HOST MYSQL_USER MYSQL_DATABASE MYSQL_PWD; do
    if [[ -z "${!name:-}" ]]; then
      echo "error: $name is required for fixture cleanup" >&2
      return 2
    fi
  done
  if [[ "$MYSQL_DATABASE" != "kbap-dev" ]]; then
    echo "error: fixture cleanup is restricted to the kbap-dev schema" >&2
    return 2
  fi
  if [[ ! "$MYSQL_HOST" =~ ^kbap-db-devstg[-a-z0-9]*\.[a-z0-9-]+\.ap-northeast-2\.rds\.amazonaws\.com$ ]]; then
    if [[ "${TEST_MODE:-false}" != "true" || ! "$MYSQL_HOST" =~ ^127\.0\.0\.1$ ]]; then
      echo "error: fixture cleanup is restricted to the documented dev RDS host; loopback requires TEST_MODE=true" >&2
      return 2
    fi
  fi
  require_commands mysql jq python3
  [[ -r "$capture_sql" && -r "$cleanup_sql" ]] || {
    echo "error: fixture state SQL scripts are required" >&2
    return 2
  }
}

validate_run_target() {
  if [[ ! "$1" =~ ^[a-zA-Z0-9._-]+-[a-zA-Z0-9._-]+$ || ! "$2" =~ ^[a-zA-Z0-9._-]+$ ]]; then
    echo "error: RUN_ID and TARGET must match the campaign-target contract" >&2
    return 2
  fi
}

mysql_dev() {
  mysql \
    --host="$MYSQL_HOST" \
    --user="$MYSQL_USER" \
    --database="$MYSQL_DATABASE" \
    --ssl-mode=REQUIRED \
    --connect-timeout=5 \
    --batch \
    --raw \
    --skip-column-names
}

if [[ "${1:-}" == "--check" && $# -eq 3 ]]; then
  validate_capabilities "$2" "$3"
  validate_database
  exit 0
fi

if [[ "${1:-}" == "--capture" && $# -eq 7 ]]; then
  run_id=$2
  target=$3
  state_capability=$4
  object_cleanup=$5
  fixture_path=$6
  snapshot_path=$7
  validate_run_target "$run_id" "$target"
  validate_capabilities "$state_capability" "$object_cleanup"
  validate_database
  [[ -r "$fixture_path" ]] || { echo "error: fixture file is required for state capture" >&2; exit 2; }
  blocked_ids=$(jq -ce '.blockedMemberIds // []' "$fixture_path")
  bookmark_ids=$(jq -ce '.bookmarkFoodIds // []' "$fixture_path")
  review_ids=$(jq -ce '.reviewIds // []' "$fixture_path")
  blocked_ids_base64=$(printf '%s' "$blocked_ids" | base64 | tr -d '\n')
  bookmark_ids_base64=$(printf '%s' "$bookmark_ids" | base64 | tr -d '\n')
  review_ids_base64=$(printf '%s' "$review_ids" | base64 | tr -d '\n')
  snapshot_output=$({
    printf "SET @run_id = '%s';\n" "$run_id"
    printf "SET @target = '%s';\n" "$target"
    printf "SET @blocked_member_ids_json = CONVERT(FROM_BASE64('%s') USING utf8mb4);\n" "$blocked_ids_base64"
    printf "SET @bookmark_food_ids_json = CONVERT(FROM_BASE64('%s') USING utf8mb4);\n" "$bookmark_ids_base64"
    printf "SET @review_ids_json = CONVERT(FROM_BASE64('%s') USING utf8mb4);\n" "$review_ids_base64"
    cat "$capture_sql"
  } | mysql_dev)
  snapshot_output=$(printf '%s\n' "$snapshot_output" | sed '/^[[:space:]]*$/d')
  [[ "$snapshot_output" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || { echo "error: state capture returned an invalid snapshot" >&2; exit 1; }
  snapshot_temp="$snapshot_path.tmp.$$"
  umask 077
  printf '%s\n' "$snapshot_output" >"$snapshot_temp"
  if ! SNAPSHOT_RUN_ID="$run_id" SNAPSHOT_TARGET="$target" python3 - "$snapshot_temp" <<'PY'
import base64
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as snapshot_file:
    document = json.loads(base64.b64decode(snapshot_file.read()).decode())
valid = document.get("runId") == os.environ["SNAPSHOT_RUN_ID"] and document.get("target") == os.environ["SNAPSHOT_TARGET"]
raise SystemExit(0 if valid else 1)
PY
  then
    rm -f "$snapshot_temp"
    echo "error: state snapshot identity validation failed" >&2
    exit 1
  fi
  mv "$snapshot_temp" "$snapshot_path"
  exit 0
fi

if [[ "${1:-}" == "--restore" && $# -eq 7 ]]; then
  run_id=$2
  target=$3
  state_capability=$4
  object_cleanup=$5
  snapshot_path=$6
  task_definition_arn=$7
  validate_run_target "$run_id" "$target"
  validate_capabilities "$state_capability" "$object_cleanup"
  validate_database
  [[ -s "$snapshot_path" ]] || { echo "error: state snapshot is required for restore" >&2; exit 1; }
  snapshot_base64=$(tr -d '\r\n' <"$snapshot_path")
  [[ "$snapshot_base64" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || { echo "error: invalid state snapshot" >&2; exit 1; }
  objects_path="$snapshot_path.objects"
  if [[ ! -f "$objects_path" ]]; then
    objects_temp="$objects_path.tmp.$$"
    if ! {
      printf "SET @run_id = '%s';\n" "$run_id"
      printf "SET @target = '%s';\n" "$target"
      printf "SET @snapshot_base64 = '%s';\n" "$snapshot_base64"
      cat "$cleanup_sql"
    } | mysql_dev >"$objects_temp"; then
      rm -f "$objects_temp"
      exit 1
    fi
    mv "$objects_temp" "$objects_path"
  fi
  object_status=0
  if [[ -s "$objects_path" ]]; then
    [[ "$object_cleanup" != "none" ]] || { echo "error: state cleanup returned unexpected object references" >&2; exit 1; }
    validate_dev_aws_environment
    require_commands aws
    validate_dev_aws_account
    storage_bucket=$(resolve_dev_storage_bucket "$task_definition_arn")
    while IFS= read -r object_path; do
      [[ -z "$object_path" ]] && continue
      if [[ "$object_path" != images/* || "$object_path" == *..* || "$object_path" == *\\* || "$object_path" == *://* ]]; then
        echo "error: cleanup returned an unsafe object reference" >&2
        object_status=1
        continue
      fi
      if [[ "$object_cleanup" == "imageCompleteFixtures" && "$object_path" != *"[load:$run_id]"* ]]; then
        echo "error: image cleanup reference is missing the exact run tag" >&2
        object_status=1
        continue
      fi
      aws --profile "$PERF_AWS_PROFILE" --region "$PERF_AWS_REGION" \
        s3api delete-object --bucket "$storage_bucket" --key "$object_path" --no-cli-pager >/dev/null || object_status=$?
    done <"$objects_path"
  fi
  [[ "$object_status" -eq 0 ]] || exit "$object_status"
  rm -f "$objects_path" "$snapshot_path"
  exit 0
fi

echo "usage: $0 --check STATE_CAPABILITY OBJECT_CLEANUP | --capture RUN_ID TARGET STATE_CAPABILITY OBJECT_CLEANUP FIXTURE_PATH SNAPSHOT_PATH | --restore RUN_ID TARGET STATE_CAPABILITY OBJECT_CLEANUP SNAPSHOT_PATH TASK_DEFINITION_ARN" >&2
exit 2
