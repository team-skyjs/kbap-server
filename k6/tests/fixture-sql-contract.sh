#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
seed_sql="${1:-$repo_dir/k6/scripts/seed-fixtures.sql}"
cleanup_sql="${2:-$repo_dir/k6/scripts/cleanup-fixtures.sql}"

fail() {
  printf 'fixture SQL contract: FAIL: %s\n' "$1" >&2
  return 1
}

require_fixed() {
  local file="$1"
  local text="$2"
  local message="$3"
  grep -Fq "$text" "$file" || fail "$message"
}

require_regex() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  rg -Uq "$pattern" "$file" || fail "$message"
}

require_statement() {
  local sql="$1"
  local statement="$2"
  local message="$3"
  grep -Fq "$statement" <<<"$sql" || fail "$message"
}

validate_seed() {
  awk '
    BEGIN { RS = ";"; valid = 1; count = 0 }
    {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
      if ($0 == "") next
      if (substr(toupper($0), 1, 6) != "SELECT") valid = 0
      count++
    }
    END { exit !(valid && count == 5) }
  ' "$seed_sql" || fail 'seed must contain exactly five SELECT-only statements'
}

validate_cleanup() {
  local normalized_cleanup
  normalized_cleanup="$(tr '\n\t' '  ' <"$cleanup_sql" | tr -s ' ')"
  require_fixed "$cleanup_sql" "IF @run_id IS NULL OR @run_id = '' THEN" 'null/blank run-id guard is required'
  require_fixed "$cleanup_sql" "IF @run_id NOT REGEXP '^[0-9A-Za-z:-]+$' THEN" 'wildcard-safe run-id guard is required'
  test "$(rg -c "SIGNAL SQLSTATE '45000'" "$cleanup_sql")" -ge 2 || fail 'both run-id guards must SIGNAL SQLSTATE 45000'
  require_fixed "$cleanup_sql" 'DECLARE EXIT HANDLER FOR SQLEXCEPTION' 'SQL exception handler is required'
  require_fixed "$cleanup_sql" 'ROLLBACK;' 'cleanup errors must rollback'
  require_fixed "$cleanup_sql" 'RESIGNAL;' 'cleanup errors must propagate'
  require_fixed "$cleanup_sql" 'START TRANSACTION;' 'cleanup must start a transaction'
  require_fixed "$cleanup_sql" 'COMMIT;' 'cleanup must commit explicitly'
  require_regex "$cleanup_sql" "FROM food_review\\n[[:space:]]+WHERE member_id = 35\\n[[:space:]]+AND content LIKE CONCAT\\('%\\[load:', @run_id, '\\]%'\\);" 'review fixture selection must use member 35 and the exact run tag'
  require_regex "$cleanup_sql" "DELETE FROM report\\n[[:space:]]+WHERE reporter_member_id = 35\\n[[:space:]]+AND detail LIKE CONCAT\\('%\\[load:', @run_id, '\\]%'\\);" 'report cleanup must use member 35 and the exact run tag'
  require_statement "$normalized_cleanup" "DELETE r FROM report r JOIN load_review_ids fixture ON fixture.review_id = r.target_id WHERE r.target_type = 'REVIEW';" 'report delete must join fixture.review_id to report.target_id'
  require_statement "$normalized_cleanup" 'DELETE child FROM review_like child JOIN load_review_ids fixture ON fixture.review_id = child.review_id;' 'review_like delete must join fixture.review_id to review_like.review_id'
  require_statement "$normalized_cleanup" 'DELETE child FROM member_ranking_event child JOIN load_review_ids fixture ON fixture.review_id = child.review_id;' 'ranking event delete must join fixture.review_id to ranking_event.review_id'
  require_statement "$normalized_cleanup" 'DELETE review FROM food_review review JOIN load_review_ids fixture ON fixture.review_id = review.id;' 'review delete must join fixture.review_id to food_review.id'
  test "$(rg -c 'JOIN load_review_ids fixture' "$cleanup_sql")" = 4 || fail 'child/review deletes must join the tagged fixture set'
  test "$(rg -c '^[[:space:]]+DELETE' "$cleanup_sql")" = 5 || fail 'cleanup delete surface changed'
  test "$(rg -c '^[[:space:]]+UPDATE' "$cleanup_sql")" = 1 || fail 'cleanup update surface changed'
  require_fixed "$cleanup_sql" 'UPDATE member' 'only member counters may be updated'
  require_fixed "$cleanup_sql" 'WHERE id = 35;' 'member counter update must be pinned to member 35'
  if rg -qi '^[[:space:]]*(DELETE([[:space:]]+FROM)?[[:space:]]+(member|food)\b|TRUNCATE|ALTER|DROP[[:space:]]+TABLE)' "$cleanup_sql"; then
    fail 'cleanup must not delete or alter member/food master tables'
  fi
}

validate_contract() {
  validate_seed
  validate_cleanup
}

validate_contract

if [[ $# -eq 0 ]]; then
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "$temp_dir"' EXIT

  cp "$seed_sql" "$temp_dir/seed.sql"
  printf '%s\n' 'DELETE FROM member WHERE id = 35;' >>"$temp_dir/seed.sql"
  if "$0" "$temp_dir/seed.sql" "$cleanup_sql" >/dev/null 2>&1; then
    fail 'seed mutation escaped the SELECT-only contract'
  fi

  sed "s/WHERE member_id = 35/WHERE member_id > 0/" "$cleanup_sql" >"$temp_dir/broad-cleanup.sql"
  if "$0" "$seed_sql" "$temp_dir/broad-cleanup.sql" >/dev/null 2>&1; then
    fail 'broad member scope escaped the cleanup contract'
  fi

  sed '/AND content LIKE CONCAT/d' "$cleanup_sql" >"$temp_dir/untagged-cleanup.sql"
  if "$0" "$seed_sql" "$temp_dir/untagged-cleanup.sql" >/dev/null 2>&1; then
    fail 'untagged review scope escaped the cleanup contract'
  fi

  sed "s/0-9A-Za-z:-/0-9A-Za-z_:%-/" "$cleanup_sql" >"$temp_dir/wildcard-cleanup.sql"
  if "$0" "$seed_sql" "$temp_dir/wildcard-cleanup.sql" >/dev/null 2>&1; then
    fail 'LIKE wildcard run-id escaped the cleanup contract'
  fi

  sed 's/ON fixture.review_id = r.target_id/ON 1=1/' "$cleanup_sql" >"$temp_dir/cartesian-cleanup.sql"
  if "$0" "$seed_sql" "$temp_dir/cartesian-cleanup.sql" >/dev/null 2>&1; then
    fail 'cartesian child join escaped the cleanup contract'
  fi

  cp "$cleanup_sql" "$temp_dir/master-cleanup.sql"
  printf '%s\n' 'DELETE FROM food;' >>"$temp_dir/master-cleanup.sql"
  if "$0" "$seed_sql" "$temp_dir/master-cleanup.sql" >/dev/null 2>&1; then
    fail 'master-table deletion escaped the cleanup contract'
  fi
fi

printf '%s\n' 'fixture SQL contract: PASS'
