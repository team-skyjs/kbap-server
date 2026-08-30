#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
fixture_path="${FIXTURE_PATH:-$repo_dir/k6/fixtures/dev.json}"

for dependency in python3 k6 aws jq; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    printf 'error: required command not found: %s\n' "$dependency" >&2
    exit 2
  fi
done

if [[ ! -f "$fixture_path" ]]; then
  printf 'error: fixture file not found: %s\n' "$fixture_path" >&2
  exit 2
fi
if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  printf 'error: ACCESS_TOKEN is required\n' >&2
  exit 2
fi
if [[ ! -x "$script_dir/run-endpoint.sh" ]]; then
  printf 'error: endpoint runner is not executable\n' >&2
  exit 2
fi

cd "$repo_dir"
exec python3 -m tools.perf_dashboard.server "$@"
