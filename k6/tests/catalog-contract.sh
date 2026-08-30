#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="$repo_dir/k6/endpoints/targets.json"
temp_dir="$(mktemp -d)"
mock_pid=""

cleanup() {
  if [[ -n "$mock_pid" ]]; then
    kill "$mock_pid" 2>/dev/null || true
    wait "$mock_pid" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

cat >"$temp_dir/expected-keys" <<'EOF'
app-version
home-auth
home-guest
ingredients-ko
ingredients-en
ingredient-diets-ko
ingredient-diets-en
member-profile
member-ranking
member-blocks
foods-auth
foods-guest
foods-next
foods-search-all-ko-hit
foods-search-all-ko-miss
foods-search-all-en-hit
foods-search-scanned
foods-search-next
foods-scanned
foods-scanned-next
food-detail-auth
food-detail-guest
bookmarks
bookmarks-next
reviews-guest-latest
reviews-auth-latest
reviews-rating-high
reviews-rating-low
reviews-food-count
reviews-helpful
reviews-next
reviews-me
reviews-me-next
orders-10
orders-30
orders-next
order-detail
EOF

jq -e '
  type == "object" and
  (.targets | type == "array" and length > 0) and
  all(.targets[];
    type == "object" and
    (["key", "label", "method", "route", "suite", "risk", "defaultProfile", "defaultEnabled"] - keys | length == 0) and
    (.key | type == "string" and length > 0) and
    (.label | type == "string" and length > 0) and
    (.method | type == "string" and length > 0) and
    (.route | type == "string" and startswith("/api/")) and
    (.suite | IN("read", "reversible-write", "fixture-write", "external")) and
    (.risk | IN("safe", "fixture", "cost")) and
    (.defaultProfile | type == "string" and length > 0) and
    (.defaultEnabled | type == "boolean")
  )
' "$manifest" >/dev/null

jq -e '([.targets[].key] | length) == ([.targets[].key] | unique | length)' "$manifest" >/dev/null
jq -e 'all(.targets[]; .risk == "safe" or (.defaultEnabled | not))' "$manifest" >/dev/null

jq -r '.targets[].key' "$manifest" >"$temp_dir/actual-keys"
diff -u "$temp_dir/expected-keys" "$temp_dir/actual-keys"

if rg -n '/api/community(?:/|$)' \
  "$repo_dir/k6/endpoints" \
  "$repo_dir/k6/fixtures"; then
  printf '%s\n' 'community endpoint is excluded from the performance catalog' >&2
  exit 1
fi

cat >"$temp_dir/fixtures.json" <<'EOF'
{
  "memberId": 35,
  "foodId": 1,
  "foodKeyword": "김치",
  "blockedMemberId": 36,
  "reviewId": 1,
  "orderId": 1,
  "foodCursor": 100,
  "bookmarkCursor": 100,
  "scanCursor": 100,
  "reviewCursor": 100
}
EOF

while IFS= read -r target; do
  k6 inspect \
    -e TARGET="$target" \
    -e BASE_URL=http://127.0.0.1:18081 \
    -e ACCESS_TOKEN=test-token \
    -e RUN_ID=catalog-contract \
    -e REPORT_DIR="$temp_dir/reports/$target" \
    -e FIXTURE_PATH="$temp_dir/fixtures.json" \
    "$repo_dir/k6/endpoint.js" >/dev/null
done <"$temp_dir/actual-keys"

cat >"$temp_dir/expected-requests" <<'EOF'
GET	/api/app-version	1.0	false
GET	/api/home?lang=ko	1.0	true
GET	/api/home?lang=ko	1.0	false
GET	/api/ingredients?lang=ko	1.0	false
GET	/api/ingredients?lang=en	1.0	false
GET	/api/ingredients/diets?lang=ko	1.0	false
GET	/api/ingredients/diets?lang=en	1.0	false
GET	/api/members/me/profile	1.0	true
GET	/api/members/me/ranking	1.0	true
GET	/api/members/me/blocks	1.0	true
GET	/api/foods?lang=ko	1.0	true
GET	/api/foods?lang=ko	1.0	false
GET	/api/foods?cursor=100&lang=ko	1.0	true
GET	/api/foods/search?keyword=%EA%B9%80%EC%B9%98&lang=ko&scope=all	1.0	true
GET	/api/foods/search?keyword=__kbap_load_test_missing__&lang=ko&scope=all	1.0	true
GET	/api/foods/search?keyword=%EA%B9%80%EC%B9%98&lang=en&scope=all	1.0	true
GET	/api/foods/search?keyword=%EA%B9%80%EC%B9%98&lang=ko&scope=scanned	1.0	true
GET	/api/foods/search?cursor=100&keyword=%EA%B9%80%EC%B9%98&lang=ko&scope=all	1.0	true
GET	/api/foods/scanned?lang=ko	1.0	true
GET	/api/foods/scanned?cursor=100&lang=ko	1.0	true
GET	/api/foods/1?lang=ko	1.0	true
GET	/api/foods/1?lang=ko	1.0	false
GET	/api/bookmarks?lang=ko	1.0	true
GET	/api/bookmarks?cursor=100&lang=ko	1.0	true
GET	/api/reviews?foodId=1&lang=ko&sort=latest	1.0	false
GET	/api/reviews?foodId=1&lang=ko&sort=latest	1.0	true
GET	/api/reviews?foodId=1&lang=ko&sort=rating_high	1.0	true
GET	/api/reviews?foodId=1&lang=ko&sort=rating_low	1.0	true
GET	/api/reviews?foodId=1&lang=ko&sort=food_review_count	1.0	true
GET	/api/reviews?foodId=1&lang=ko&sort=helpful	1.0	true
GET	/api/reviews?cursor=100&foodId=1&lang=ko&sort=latest	1.0	true
GET	/api/reviews/me?lang=ko	1.0	true
GET	/api/reviews/me?cursor=100&lang=ko	1.0	true
GET	/api/orders?size=10	1.0	true
GET	/api/orders?size=30	1.0	true
GET	/api/orders?cursor=1&size=10	1.0	true
GET	/api/orders/1	1.0	true
EOF

python3 "$repo_dir/k6/tests/mock-server.py" >"$temp_dir/mock-server.log" 2>&1 &
mock_pid=$!
for _ in {1..50}; do
  if curl --silent --fail http://127.0.0.1:18081/health >/dev/null; then
    break
  fi
  if ! kill -0 "$mock_pid" 2>/dev/null; then
    exit 1
  fi
  sleep 0.1
done
curl --silent --fail http://127.0.0.1:18081/health >/dev/null

while IFS= read -r target; do
  report_dir="$temp_dir/reports/$target"
  mkdir -p "$report_dir"
  k6 run --quiet \
    -e TARGET="$target" \
    -e BASE_URL=http://127.0.0.1:18081 \
    -e ACCESS_TOKEN=test-token \
    -e RUN_ID=catalog-contract \
    -e REPORT_DIR="$report_dir" \
    -e FIXTURE_PATH="$temp_dir/fixtures.json" \
    -e PROFILE=smoke \
    "$repo_dir/k6/endpoint.js" >/dev/null
done <"$temp_dir/actual-keys"

kill "$mock_pid"
wait "$mock_pid" 2>/dev/null || true
mock_pid=""

rg '^REQUEST\t' "$temp_dir/mock-server.log" | cut -f2- >"$temp_dir/actual-requests"
sort "$temp_dir/expected-requests" >"$temp_dir/expected-requests.sorted"
sort "$temp_dir/actual-requests" >"$temp_dir/actual-requests.sorted"
diff -u "$temp_dir/expected-requests.sorted" "$temp_dir/actual-requests.sorted"

printf 'k6 catalog contract: PASS (%s targets)\n' "$(wc -l <"$temp_dir/actual-keys" | tr -d ' ')"
