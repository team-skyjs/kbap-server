#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="$repo_dir/k6/endpoints/targets.json"
fixture_example="$repo_dir/k6/fixtures/dev.example.json"
expected_keys="$repo_dir/k6/tests/catalog-expected-keys.txt"
expected_requests="$repo_dir/k6/tests/catalog-expected-requests.tsv"
expected_bodies="$repo_dir/k6/tests/catalog-expected-bodies.tsv"
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

"$repo_dir/k6/tests/fixture-sql-contract.sh"
"$repo_dir/k6/tests/state-lifecycle-sql-contract.sh"
"$repo_dir/k6/tests/runtime-safety-contract.sh"

jq -e '
  type == "object" and
  (.targets | type == "array" and length > 0) and
  all(.targets[];
    type == "object" and
    (["key", "label", "method", "route", "suite", "risk", "defaultProfile", "defaultEnabled", "requestsPerIteration", "stateCapability", "objectCleanup"] - keys | length == 0) and
    (.key | type == "string" and length > 0) and
    (.label | type == "string" and length > 0) and
    (.method | IN("GET", "POST", "PATCH", "DELETE")) and
    (.route | type == "string" and startswith("/api/")) and
    (.suite | IN("read", "reversible-write", "fixture-write", "external")) and
    (.risk | IN("safe", "fixture", "cost")) and
    (.defaultProfile | IN("read", "write", "external")) and
    (.defaultEnabled | type == "boolean") and
    (.requestsPerIteration | type == "number" and . >= 1 and floor == .) and
    (.stateCapability | IN("none", "snapshot-restore", "tagged-cleanup", "scan-cleanup")) and
    (.objectCleanup | IN("none", "imageCompleteFixtures", "scanGeneratedFoodImageRefs"))
  )
' "$manifest" >/dev/null

jq -e '([.targets[].key] | length) == ([.targets[].key] | unique | length)' "$manifest" >/dev/null
jq -e 'all(.targets[]; .requestsPerIteration == (if (.key == "scan-v2-krw" or .key == "scan-v2-usd") then 2 else 1 end))' "$manifest" >/dev/null
jq -e 'all(.targets[]; .risk == "safe" or (.defaultEnabled | not))' "$manifest" >/dev/null
jq -e 'all(.targets[];
  if .suite == "read" then .risk == "safe" and .defaultProfile == "read" and .defaultEnabled
  elif .suite == "reversible-write" then .risk == "safe" and .defaultProfile == "write" and .defaultEnabled
  elif .suite == "fixture-write" then .risk == "fixture" and .defaultProfile == "write" and (.defaultEnabled | not)
  else .suite == "external" and .risk == "cost" and .defaultProfile == "external" and (.defaultEnabled | not)
  end
)' "$manifest" >/dev/null
jq -e '
  [.targets[] | select(.suite == "reversible-write") | .key] ==
    ["member-profile-v1","member-profile-v11","member-block","member-unblock","bookmark-add","bookmark-remove","review-update","review-like","review-unlike"] and
  [.targets[] | select(.suite == "fixture-write") | .key] ==
    ["review-create","review-delete","report-create","image-upload-url","image-complete","order-create-no-location"] and
  [.targets[] | select(.suite == "external") | .key] ==
    ["order-create-location","place-nearby","place-search","scan-ticket","scan-v1","scan-v2-krw","scan-v2-usd"]
' "$manifest" >/dev/null
jq -e '
  [.targets[] | select(.stateCapability == "snapshot-restore") | .key] ==
    ["member-profile-v1","member-profile-v11","member-block","member-unblock","bookmark-add","bookmark-remove","review-update","review-delete","review-like","review-unlike"] and
  [.targets[] | select(.stateCapability == "tagged-cleanup") | .key] ==
    ["review-create","report-create","image-complete","order-create-no-location","order-create-location"] and
  [.targets[] | select(.stateCapability == "scan-cleanup") | .key] ==
    ["scan-v1","scan-v2-krw","scan-v2-usd"] and
  [.targets[] | select(.objectCleanup == "imageCompleteFixtures") | .key] == ["image-complete"] and
  [.targets[] | select(.objectCleanup == "scanGeneratedFoodImageRefs") | .key] == ["scan-v1","scan-v2-krw","scan-v2-usd"] and
  all(.targets[]; if .stateCapability == "none" then .objectCleanup == "none" else true end)
' "$manifest" >/dev/null
jq -e '(.scanCursor | type == "number") and (.scanCursor >= 0) and (.scanCursor | floor == .)' "$fixture_example" >/dev/null
jq -e '
  (.profileV1 | type == "object") and (.profileV11 | type == "object") and
  all(.blockedMemberIds, .bookmarkFoodIds, .scanHistoryFoodIds, .reviewIds, .reportReviewIds;
    type == "array" and length > 0) and
  (.imageCompleteFixtures | type == "array" and length > 0 and all(.[]; has("path", "contentType", "size"))) and
  (.orderFixtures | type == "array" and length > 0 and all(.[]; has("imagePath", "foodId", "menuName", "price"))) and
  (.scanImagePath | type == "string" and length > 0)
' "$fixture_example" >/dev/null

jq -r '.targets[].key' "$manifest" >"$temp_dir/actual-keys"
diff -u "$expected_keys" "$temp_dir/actual-keys"

jq -r '.targets[] | [.key, .method, .route, .defaultProfile] | @tsv' "$manifest" \
  | sort >"$temp_dir/manifest-contracts"
(
  cd "$repo_dir"
  node --input-type=module -e '
    import { endpointCatalog } from "./k6/endpoints/index.js";
    for (const endpoint of endpointCatalog) {
      console.log([endpoint.key, endpoint.method, endpoint.route, endpoint.kind].join("\t"));
    }
  '
) >"$temp_dir/registry-contracts"

cut -f1 "$temp_dir/registry-contracts" >"$temp_dir/registry-keys"
if [[ "$(wc -l <"$temp_dir/registry-keys" | tr -d ' ')" != "$(sort -u "$temp_dir/registry-keys" | wc -l | tr -d ' ')" ]]; then
  printf '%s\n' 'duplicate endpoint key in runtime registry catalog' >&2
  exit 1
fi
if rg -n '/api/community(?:/|$)' "$repo_dir/k6" --glob '*.js' --glob '*.json'; then
  printf '%s\n' 'community endpoint is excluded from the performance catalog' >&2
  exit 1
fi

while IFS= read -r target; do
  k6 inspect \
    -e TARGET="$target" \
    -e BASE_URL="$base_url" \
    -e ACCESS_TOKEN=test-token \
    -e RUN_ID=catalog-contract \
    -e REPORT_DIR="$temp_dir/reports/$target" \
    -e FIXTURE_PATH="$catalog_fixtures" \
    "$repo_dir/k6/endpoint.js" >/dev/null
done <"$temp_dir/actual-keys"

sort "$temp_dir/registry-contracts" >"$temp_dir/registry-contracts.sorted"
diff -u "$temp_dir/manifest-contracts" "$temp_dir/registry-contracts.sorted"

if k6 inspect -e TARGET=scan-ticket -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=cap -e REPORT_DIR="$temp_dir/cap" -e FIXTURE_PATH="$catalog_fixtures" \
  -e VUS=201 -e ITERATIONS=1 "$repo_dir/k6/endpoint.js" >"$temp_dir/cap.out" 2>&1; then
  printf '%s\n' 'external total iteration cap was not enforced' >&2
  exit 1
fi
rg -q 'external total iterations must not exceed 200' "$temp_dir/cap.out"
if k6 inspect -e TARGET=scan-ticket -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=bypass -e REPORT_DIR="$temp_dir/bypass" -e FIXTURE_PATH="$catalog_fixtures" \
  -e PROFILE=write "$repo_dir/k6/endpoint.js" >"$temp_dir/bypass.out" 2>&1; then
  printf '%s\n' 'external target accepted an unbounded profile override' >&2
  exit 1
fi
rg -q 'external targets require external or smoke profile' "$temp_dir/bypass.out"
if k6 inspect -e TARGET=order-create-location -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=order-cap -e REPORT_DIR="$temp_dir/order-cap" -e FIXTURE_PATH="$catalog_fixtures" \
  -e VUS=201 -e ITERATIONS=1 "$repo_dir/k6/endpoint.js" >"$temp_dir/order-cap.out" 2>&1; then
  printf '%s\n' 'location order did not enforce the external total iteration cap' >&2
  exit 1
fi
rg -q 'external total iterations must not exceed 200' "$temp_dir/order-cap.out"
start_mock

while IFS= read -r target; do
  report_dir="$temp_dir/reports/$target"
  mkdir -p "$report_dir"
  k6 run --quiet \
    -e TARGET="$target" \
    -e BASE_URL="$base_url" \
    -e ACCESS_TOKEN=test-token \
    -e RUN_ID=catalog-contract \
    -e REPORT_DIR="$report_dir" \
    -e FIXTURE_PATH="$catalog_fixtures" \
    -e PROFILE=smoke \
    "$repo_dir/k6/endpoint.js" >/dev/null
done <"$temp_dir/actual-keys"

stop_mock

rg '^REQUEST\t' "$temp_dir/mock-server.log" | cut -f2- >"$temp_dir/actual-requests"
sort "$expected_requests" >"$temp_dir/expected-requests.sorted"
sort "$temp_dir/actual-requests" >"$temp_dir/actual-requests.sorted"
diff -u "$temp_dir/expected-requests.sorted" "$temp_dir/actual-requests.sorted"
rg '^BODY\t' "$temp_dir/mock-server.log" | cut -f2- | sort >"$temp_dir/actual-bodies.sorted"
sort "$expected_bodies" >"$temp_dir/expected-bodies.sorted"
diff -u "$temp_dir/expected-bodies.sorted" "$temp_dir/actual-bodies.sorted"

start_mock
report_dir="$temp_dir/contended"
mkdir -p "$report_dir"
k6 run --quiet -e TARGET=member-block -e BASE_URL="$base_url" -e ACCESS_TOKEN=test-token \
  -e RUN_ID=contended -e REPORT_DIR="$report_dir" -e FIXTURE_PATH="$catalog_fixtures" \
  -e PROFILE=smoke -e CONTENDED=true "$repo_dir/k6/endpoint.js" >/dev/null
stop_mock
grep -Fq $'BODY\tPOST\t/api/members/me/blocks\t{"memberId":36}' "$temp_dir/mock-server.log"

printf 'k6 catalog contract: PASS (%s targets)\n' "$(wc -l <"$temp_dir/actual-keys" | tr -d ' ')"
