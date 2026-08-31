#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
capture_sql="${1:-$repo_dir/k6/scripts/capture-fixtures.sql}"
cleanup_sql="${2:-$repo_dir/k6/scripts/cleanup-fixtures.sql}"

fail() {
  printf 'state lifecycle SQL contract: FAIL: %s\n' "$1" >&2
  exit 1
}

require_fixed() {
  rg -Fq "$2" "$1" || fail "$3"
}

[[ -r "$capture_sql" ]] || fail 'capture-fixtures.sql is required'
[[ -r "$cleanup_sql" ]] || fail 'cleanup-fixtures.sql is required'

for variable in run_id target blocked_member_ids_json bookmark_food_ids_json review_ids_json; do
  require_fixed "$capture_sql" "@$variable" "capture must consume @$variable"
done
require_fixed "$capture_sql" 'snapshot_base64' 'capture must emit snapshot_base64'
require_fixed "$capture_sql" "'memberProfile'" 'member profile snapshot is required'
require_fixed "$capture_sql" "'memberCounters'" 'member counter snapshot is required'
require_fixed "$capture_sql" "'memberBlocks'" 'block existence/status snapshot is required'
require_fixed "$capture_sql" "'bookmarks'" 'bookmark existence/status snapshot is required'
require_fixed "$capture_sql" "'reviewLikes'" 'review-like existence/status snapshot is required'
require_fixed "$capture_sql" "'reviews'" 'review update/delete full snapshot is required'
require_fixed "$capture_sql" "'rankingEventHighWatermark'" 'ranking event watermark is required'
require_fixed "$capture_sql" "'scanHistoryHighWatermark'" 'scan history watermark is required'
require_fixed "$capture_sql" "'foodHighWatermark'" 'food watermark is required'

for variable in run_id target snapshot_base64; do
  require_fixed "$cleanup_sql" "@$variable" "cleanup must consume @$variable"
done
require_fixed "$cleanup_sql" 'START TRANSACTION;' 'cleanup must be transactional'
require_fixed "$cleanup_sql" 'ROLLBACK;' 'cleanup must roll back on error'
require_fixed "$cleanup_sql" 'RESIGNAL;' 'cleanup errors must propagate'
require_fixed "$cleanup_sql" 'BINARY content LIKE BINARY CONCAT' 'review tags must compare case-sensitively'
require_fixed "$cleanup_sql" 'BINARY detail LIKE BINARY CONCAT' 'report tags must compare case-sensitively'
require_fixed "$cleanup_sql" 'BINARY item.menu_name LIKE BINARY CONCAT' 'order tags must compare case-sensitively'
require_fixed "$cleanup_sql" 'BINARY object_path LIKE BINARY CONCAT' 'image tags must compare case-sensitively'
require_fixed "$cleanup_sql" "JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.runId'))" 'snapshot run-id binding is required'
require_fixed "$cleanup_sql" "JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.target'))" 'snapshot target binding is required'
require_fixed "$cleanup_sql" "IF BINARY snapshot_run_id <> BINARY @run_id" 'snapshot run ID must be case-sensitive'
require_fixed "$cleanup_sql" "IF BINARY snapshot_target <> BINARY @target" 'snapshot target must be case-sensitive'
require_fixed "$cleanup_sql" "@target IN ('member-profile-v1', 'member-profile-v11')" 'profile restore targets are required'
require_fixed "$cleanup_sql" "@target IN ('member-block', 'member-unblock')" 'block restore targets are required'
require_fixed "$cleanup_sql" "@target IN ('bookmark-add', 'bookmark-remove')" 'bookmark restore targets are required'
require_fixed "$cleanup_sql" "@target IN ('review-like', 'review-unlike')" 'review-like restore targets are required'
require_fixed "$cleanup_sql" "@target IN ('review-update', 'review-delete')" 'review restore targets are required'
require_fixed "$cleanup_sql" "@target IN ('scan-v1', 'scan-v2-krw', 'scan-v2-usd')" 'scan cleanup targets are required'
require_fixed "$cleanup_sql" 'member_id = 35' 'all member-owned restore scope must be pinned to member 35'
require_fixed "$cleanup_sql" 'scan_history.id > scan_history_high_watermark' 'scan cleanup must use the member 35 high-watermark'
require_fixed "$cleanup_sql" "candidate_food.content_status = 'FAILED'" 'current-schema incomplete food scope must be FAILED only'
require_fixed "$cleanup_sql" 'candidate_food.id > food_high_watermark' 'scan food cleanup must use food high-watermark'
require_fixed "$cleanup_sql" 'SIGNAL SQLSTATE' 'cleanup residuals must fail closed'
require_fixed "$cleanup_sql" 'object_cleanup_path' 'scan-generated object paths must be returned'

if rg -q 'DELETE FROM (member|food)[ ;]' "$cleanup_sql"; then
  fail 'member/food must not be blanket-deleted'
fi

if [[ $# -eq 0 ]]; then
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "$temp_dir"' EXIT

  for tagged_column in content detail item.menu_name object_path; do
    mutation_name="${tagged_column//./-}"
    sed "s/BINARY $tagged_column LIKE BINARY CONCAT/$tagged_column LIKE CONCAT/" \
      "$cleanup_sql" >"$temp_dir/case-insensitive-$mutation_name.sql"
    if "$0" "$capture_sql" "$temp_dir/case-insensitive-$mutation_name.sql" >/dev/null 2>&1; then
      fail "case-only $tagged_column distractor escaped the contract"
    fi
  done

  sed 's/scan_history.id > scan_history_high_watermark/scan_history.id > 0/' "$cleanup_sql" >"$temp_dir/broad-scan.sql"
  if "$0" "$capture_sql" "$temp_dir/broad-scan.sql" >/dev/null 2>&1; then
    fail 'broad scan-history mutation escaped the contract'
  fi

  sed "s/candidate_food.content_status = 'FAILED'/candidate_food.content_status <> 'READY'/" "$cleanup_sql" >"$temp_dir/broad-food.sql"
  if "$0" "$capture_sql" "$temp_dir/broad-food.sql" >/dev/null 2>&1; then
    fail 'broad generated-food mutation escaped the contract'
  fi
fi

printf '%s\n' 'state lifecycle SQL contract: PASS'
