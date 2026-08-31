#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
catalog_fixtures="$repo_dir/k6/tests/catalog-fixtures.json"
temp_dir="$(mktemp -d)"
mock_pid=""
base_url="http://127.0.0.1:1"

cleanup() {
  if [[ -n "$mock_pid" ]]; then
    kill "$mock_pid" 2>/dev/null || true
    wait "$mock_pid" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

start_mock() {
  local ticket_mode="${1:-success}"
  local ticket_failure=false
  local ticket_missing=false
  case "$ticket_mode" in
    success) ;;
    failure) ticket_failure=true ;;
    missing) ticket_missing=true ;;
    *) printf 'unknown mock ticket mode: %s\n' "$ticket_mode" >&2; exit 1 ;;
  esac
  : >"$temp_dir/mock-server.log"
  MOCK_PORT=0 MOCK_TICKET_FAILURE="$ticket_failure" MOCK_TICKET_MISSING="$ticket_missing" \
    python3 "$repo_dir/k6/tests/mock-server.py" >"$temp_dir/mock-server.log" 2>&1 &
  mock_pid=$!
  for _ in {1..50}; do
    mock_port="$(awk -F '\t' '$1 == "READY" { print $2 }' "$temp_dir/mock-server.log")"
    if [[ -n "$mock_port" ]]; then
      base_url="http://127.0.0.1:$mock_port"
      curl --silent --fail "$base_url/health" >/dev/null && return
    fi
    kill -0 "$mock_pid" 2>/dev/null || exit 1
    sleep 0.1
  done
  printf '%s\n' 'mock server did not become ready' >&2
  exit 1
}

stop_mock() {
  kill "$mock_pid"
  wait "$mock_pid" 2>/dev/null || true
  mock_pid=""
}

fixtures_for_run() {
  local run_id="$1"
  local output="$2"
  jq --arg tag "[load:$run_id]" \
    '.imageCompleteFixtures |= map(.path |= sub("\\[load:[^]]+\\]"; $tag))' \
    "$catalog_fixtures" >"$output"
}

for invalid_offset in -1 1.5 invalid; do
  if k6 inspect -e TARGET=image-complete -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
    -e RUN_ID=offset-invalid -e REPORT_DIR="$temp_dir/offset-invalid" -e FIXTURE_PATH="$catalog_fixtures" \
    -e PROFILE=smoke -e FIXTURE_OFFSET="$invalid_offset" "$repo_dir/k6/endpoint.js" >"$temp_dir/offset-invalid.out" 2>&1; then
    printf 'invalid FIXTURE_OFFSET was accepted: %s\n' "$invalid_offset" >&2
    exit 1
  fi
  rg -q 'FIXTURE_OFFSET must be a non-negative integer' "$temp_dir/offset-invalid.out"
done

start_mock
report_dir="$temp_dir/offset-selection"
mkdir -p "$report_dir"
k6 run --quiet -e TARGET=image-complete -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=catalog-contract -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$catalog_fixtures" \
  -e PROFILE=smoke -e FIXTURE_OFFSET=1 "$repo_dir/k6/endpoint.js" >/dev/null
stop_mock
grep -Fq 'images/menu-scan/[load:catalog-contract]/catalog-2.jpg' "$temp_dir/mock-server.log"
if grep -Fq 'images/menu-scan/[load:catalog-contract]/catalog-1.jpg' "$temp_dir/mock-server.log"; then
  printf '%s\n' 'FIXTURE_OFFSET=1 reused fixture index 0' >&2
  exit 1
fi

start_mock
report_dir="$temp_dir/offset-exhausted"
mkdir -p "$report_dir"
if k6 run --quiet -e TARGET=image-complete -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=catalog-contract -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$catalog_fixtures" \
  -e PROFILE=smoke -e FIXTURE_OFFSET=2 "$repo_dir/k6/endpoint.js" >/dev/null 2>&1; then
  printf '%s\n' 'fixture exhaustion unexpectedly passed thresholds' >&2
  exit 1
fi
stop_mock
if rg -q $'^REQUEST\t' "$temp_dir/mock-server.log"; then
  printf '%s\n' 'fixture exhaustion must not issue HTTP' >&2
  exit 1
fi
jq -e '.data.metrics.fixture_exhausted.values.count == 1 and .data.metrics.fixture_exhausted.thresholds["count==0"].ok == false' \
  "$report_dir/summary.json" >/dev/null

untagged_fixtures="$temp_dir/untagged-image-fixtures.json"
jq '.imageCompleteFixtures[0].path = "images/menu-scan/untagged.jpg"' "$catalog_fixtures" >"$untagged_fixtures"
start_mock
report_dir="$temp_dir/untagged-image"
mkdir -p "$report_dir"
if k6 run --quiet -e TARGET=image-complete -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=catalog-contract -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$untagged_fixtures" \
  -e PROFILE=smoke "$repo_dir/k6/endpoint.js" >/dev/null 2>&1; then
  printf '%s\n' 'untagged image fixture was accepted' >&2
  exit 1
fi
stop_mock
if rg -q $'^REQUEST\t' "$temp_dir/mock-server.log"; then
  printf '%s\n' 'untagged image fixture must not issue HTTP' >&2
  exit 1
fi
jq -e '.data.metrics.fixture_exhausted.values.count == 1 and .data.metrics.fixture_exhausted.thresholds["count==0"].ok == false' \
  "$report_dir/summary.json" >/dev/null

for target in review-delete report-create; do
  start_mock
  report_dir="$temp_dir/one-use/$target"
  mkdir -p "$report_dir"
  if k6 run --quiet -e TARGET="$target" -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
    -e RUN_ID=one-use -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$catalog_fixtures" \
    -e PROFILE=external -e VUS=2 -e ITERATIONS=2 "$repo_dir/k6/endpoint.js" >/dev/null 2>&1; then
    printf 'one-use exhaustion unexpectedly passed: %s\n' "$target" >&2
    exit 1
  fi
  stop_mock
  test "$(rg -c $'^REQUEST\t' "$temp_dir/mock-server.log")" = 1
  jq -e '.data.metrics.fixture_exhausted.values.count == 3 and .data.metrics.fixture_exhausted.thresholds["count==0"].ok == false' \
    "$report_dir/summary.json" >/dev/null
done

for target in image-complete order-create-no-location order-create-location; do
  start_mock
  report_dir="$temp_dir/exhaustion/$target"
  mkdir -p "$report_dir"
  scenario_fixtures="$temp_dir/fixtures-$target.json"
  fixtures_for_run fixture-exhaustion "$scenario_fixtures"
  if k6 run --quiet -e TARGET="$target" -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
    -e RUN_ID=fixture-exhaustion -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$scenario_fixtures" \
    -e PROFILE=external -e VUS=1 -e ITERATIONS=3 "$repo_dir/k6/endpoint.js" >/dev/null 2>&1; then
    printf 'fixture exhaustion unexpectedly passed: %s\n' "$target" >&2
    exit 1
  fi
  stop_mock
  test "$(rg -c $'^REQUEST\t' "$temp_dir/mock-server.log")" = 2
  test "$(rg $'^BODY\t' "$temp_dir/mock-server.log" | sort -u | wc -l | tr -d ' ')" = 2
  jq -e '.data.metrics.fixture_exhausted.values.count == 1 and .data.metrics.fixture_exhausted.thresholds["count==0"].ok == false' \
    "$report_dir/summary.json" >/dev/null
done

start_mock
report_dir="$temp_dir/scan-v2-two-iterations"
mkdir -p "$report_dir"
k6 run --quiet -e TARGET=scan-v2-krw -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=scan-two -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$catalog_fixtures" \
  -e PROFILE=external -e VUS=1 -e ITERATIONS=2 "$repo_dir/k6/endpoint.js" >/dev/null
stop_mock
test "$(grep -Fc $'REQUEST\tPOST\t/api/scans/tickets\t' "$temp_dir/mock-server.log")" = 2
test "$(grep -Fc $'REQUEST\tPOST\t/api/scans?currency=KRW&lang=ko\t' "$temp_dir/mock-server.log")" = 2

for ticket_mode in missing failure; do
  start_mock "$ticket_mode"
  report_dir="$temp_dir/ticket-$ticket_mode"
  mkdir -p "$report_dir"
  if k6 run --quiet -e TARGET=scan-v2-krw -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
    -e RUN_ID="ticket-$ticket_mode" -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$catalog_fixtures" \
    -e PROFILE=smoke "$repo_dir/k6/endpoint.js" >/dev/null 2>&1; then
    printf 'scan failure unexpectedly passed thresholds: %s\n' "$ticket_mode" >&2
    exit 1
  fi
  stop_mock
  jq -e '.data.metrics.scan_failed.values.count == 1 and .data.metrics.scan_failed.thresholds["count==0"].ok == false' \
    "$report_dir/summary.json" >/dev/null
  test "$(rg -c $'^REQUEST\t' "$temp_dir/mock-server.log")" = 1
  if grep -Fq $'REQUEST\tPOST\t/api/scans?' "$temp_dir/mock-server.log"; then
    printf 'scan request must not run after ticket failure: %s\n' "$ticket_mode" >&2
    exit 1
  fi
done

printf '%s\n' 'k6 runtime safety contract: PASS'
