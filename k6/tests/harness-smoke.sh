#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temp_dir="$(mktemp -d)"
report_dir="$temp_dir/reports"
mock_pid=""

cleanup() {
  if [[ -n "$mock_pid" ]]; then
    kill "$mock_pid" 2>/dev/null || true
    wait "$mock_pid" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

python3 "$repo_dir/k6/tests/mock-server.py" >"$temp_dir/mock-server.log" 2>&1 &
mock_pid=$!
mkdir -p "$report_dir"

for _ in {1..50}; do
  if curl --silent --fail http://127.0.0.1:18081/api/app-version >/dev/null; then
    break
  fi
  if ! kill -0 "$mock_pid" 2>/dev/null; then
    exit 1
  fi
  sleep 0.1
done

if ! curl --silent --fail http://127.0.0.1:18081/api/app-version >/dev/null; then
  exit 1
fi

k6 inspect \
  -e TARGET=app-version \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e ACCESS_TOKEN=test-token \
  -e RUN_ID=harness-smoke \
  -e REPORT_DIR="$report_dir" \
  "$repo_dir/k6/endpoint.js"

k6 run \
  -e TARGET=app-version \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e ACCESS_TOKEN=test-token \
  -e RUN_ID=harness-smoke \
  -e REPORT_DIR="$report_dir" \
  -e PROFILE=smoke \
  "$repo_dir/k6/endpoint.js"

test -s "$report_dir/report.html"
test -s "$report_dir/summary.json"
grep -q 'app-version' "$report_dir/report.html"
if grep -q 'test-token' "$report_dir/report.html" "$report_dir/summary.json"; then
  exit 1
fi
if rg -q '<script|https?://' "$report_dir/report.html"; then
  exit 1
fi

printf '%s\n' 'k6 harness smoke: PASS'
