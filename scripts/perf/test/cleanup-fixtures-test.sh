#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cleanup_script="$repo_dir/scripts/perf/cleanup-fixtures.sh"
temp_dir="$(mktemp -d)"
fake_bin="$temp_dir/bin"
mysql_calls="$temp_dir/mysql-calls"
mysql_input="$temp_dir/mysql-input"
aws_calls="$temp_dir/aws-calls"
fixture="$temp_dir/fixture.json"
task_definition_arn=arn:aws:ecs:ap-northeast-2:118178010621:task-definition/kbap-dev-ecs-api:42

cleanup() {
  rm -rf "$temp_dir"
}
trap cleanup EXIT
mkdir -p "$fake_bin"
printf '{"blockedMemberIds":[36],"bookmarkFoodIds":[1],"reviewIds":[2]}\n' >"$fixture"

cat >"$fake_bin/mysql" <<'MYSQL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_MYSQL_CALLS"
input=$(cat)
printf '%s\n' "$input" >"$FAKE_MYSQL_INPUT"
if [[ "$input" == *'kbap_capture_load_fixture_state'* ]]; then
  run_id=$(printf '%s\n' "$input" | sed -n "s/^SET @run_id = '\([^']*\)';$/\1/p" | head -1)
  target=$(printf '%s\n' "$input" | sed -n "s/^SET @target = '\([^']*\)';$/\1/p" | head -1)
  printf '{"runId":"%s","target":"%s"}' "$run_id" "$target" | base64 | tr -d '\n'
  printf '\n'
elif [[ -n "${FAKE_OBJECT_PATH:-}" ]]; then
  printf '%s\n' "$FAKE_OBJECT_PATH"
fi
MYSQL

cat >"$fake_bin/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_AWS_CALLS"
if [[ " $* " == *" sts get-caller-identity "* ]]; then
  printf '%s\n' '118178010621'
elif [[ " $* " == *" ecs describe-task-definition "* && "$*" == *"STORAGE_BUCKET"* ]]; then
  printf '%s\n' 'kbap-dev-storage-assets'
elif [[ " $* " == *" s3api delete-object "* ]]; then
  exit "${FAKE_DELETE_EXIT:-0}"
else
  exit 64
fi
AWS
chmod +x "$fake_bin/mysql" "$fake_bin/aws"

run_cleanup() {
  PATH="$fake_bin:$PATH" FAKE_MYSQL_CALLS="$mysql_calls" FAKE_MYSQL_INPUT="$mysql_input" FAKE_AWS_CALLS="$aws_calls" \
    MYSQL_HOST=kbap-db-devstg.abcdefghijkl.ap-northeast-2.rds.amazonaws.com \
    MYSQL_USER=kbap MYSQL_DATABASE=kbap-dev MYSQL_PWD=secret \
    AWS_PROFILE=kbap-infra AWS_REGION=ap-northeast-2 \
    "$@"
}

run_cleanup "$cleanup_script" --check snapshot-restore none
test ! -e "$mysql_calls"

snapshot="$temp_dir/review.snapshot"
run_cleanup "$cleanup_script" --capture 20260831T120000Z-review-update review-update snapshot-restore none "$fixture" "$snapshot"
test -s "$snapshot"
grep -q -- '--ssl-mode=REQUIRED' "$mysql_calls"
if rg -q 'secret' "$mysql_calls"; then
  printf '%s\n' 'database password appeared in mysql argv' >&2
  exit 1
fi
grep -q "^SET @run_id = '20260831T120000Z-review-update';$" "$mysql_input"
grep -q '^SET @review_ids_json = CONVERT(FROM_BASE64' "$mysql_input"
run_cleanup "$cleanup_script" --restore 20260831T120000Z-review-update review-update snapshot-restore none "$snapshot" "$task_definition_arn"
test ! -e "$snapshot"

image_snapshot="$temp_dir/image.snapshot"
run_cleanup "$cleanup_script" --capture 20260831T130000Z-image-complete image-complete tagged-cleanup imageCompleteFixtures "$fixture" "$image_snapshot"
FAKE_OBJECT_PATH='images/menu-scan/[load:20260831T130000Z-image-complete]/object.jpg' run_cleanup \
  "$cleanup_script" --restore 20260831T130000Z-image-complete image-complete tagged-cleanup imageCompleteFixtures "$image_snapshot" "$task_definition_arn"
grep -q -- '--bucket kbap-dev-storage-assets --key images/menu-scan/\[load:20260831T130000Z-image-complete\]/object.jpg' "$aws_calls"
if rg -q 's3://|https?://' "$aws_calls"; then
  printf '%s\n' 'object cleanup exposed a storage URL' >&2
  exit 1
fi

unsafe_snapshot="$temp_dir/unsafe.snapshot"
run_cleanup "$cleanup_script" --capture 20260831T140000Z-image-complete image-complete tagged-cleanup imageCompleteFixtures "$fixture" "$unsafe_snapshot"
set +e
FAKE_OBJECT_PATH='s3://other-bucket/unsafe' run_cleanup \
  "$cleanup_script" --restore 20260831T140000Z-image-complete image-complete tagged-cleanup imageCompleteFixtures "$unsafe_snapshot" "$task_definition_arn"
unsafe_status=$?
set -e
test "$unsafe_status" -ne 0
test -s "$unsafe_snapshot"

set +e
run_cleanup env TEST_MODE=false MYSQL_HOST=prod.example.com "$cleanup_script" --check tagged-cleanup none >/dev/null 2>&1
prod_status=$?
run_cleanup env MYSQL_DATABASE=kbap-prod "$cleanup_script" --check tagged-cleanup none >/dev/null 2>&1
schema_status=$?
set -e
test "$prod_status" -ne 0
test "$schema_status" -ne 0

printf '%s\n' 'fixture cleanup: PASS'
