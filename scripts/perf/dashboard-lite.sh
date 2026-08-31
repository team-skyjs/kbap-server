#!/usr/bin/env bash
# 최소 기동: python3 만 있으면 대시보드가 뜬다. AWS·docker·토큰 검증은 실제 실행(run-endpoint.sh) 시점에 이뤄진다.
# JWT 는 웹페이지의 "JWT Secret (dev)" 입력에 붙여넣으면 실행마다 회원 35 토큰을 만들어 쓴다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
port="${DASHBOARD_PORT:-8765}"

command -v python3 >/dev/null 2>&1 || { echo "error: python3 required" >&2; exit 2; }

for cmd in k6 aws jq session-manager-plugin docker; do
  command -v "$cmd" >/dev/null 2>&1 || echo "warn: '$cmd' not found — 실제 캠페인 실행 시 필요합니다" >&2
done

url="http://127.0.0.1:${port}/"
echo "dashboard: $url"
(sleep 1; command -v open >/dev/null 2>&1 && open "$url") &

cd "$repo_dir"
exec python3 -m tools.perf_dashboard.server
