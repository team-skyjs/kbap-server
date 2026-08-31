#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
fixture_path="${FIXTURE_PATH:-$repo_dir/k6/fixtures/dev.json}"
source "$script_dir/lib.sh"

for dependency in python3 k6 aws jq session-manager-plugin docker; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    printf 'error: required command not found: %s\n' "$dependency" >&2
    exit 2
  fi
done

validate_dev_aws_environment
validate_base_url "${BASE_URL:-https://dev.kbap.site}"

if [[ ! -f "$fixture_path" ]]; then
  printf 'error: fixture file not found: %s\n' "$fixture_path" >&2
  exit 2
fi
if [[ -z "${JWT_SECRET:-}" || -z "${ACCESS_TOKEN:-}" ]]; then
  printf '%s\n' 'error: JWT_SECRET and ACCESS_TOKEN are required' >&2
  printf '%s\n' 'token setup: export JWT_SECRET=<dev-secret>' >&2
  printf '%s\n' "token setup: export ACCESS_TOKEN=\"\$(python3 k6/mint-token.py 35 2)\"" >&2
  exit 2
fi
validate_access_token
validate_dev_aws_account
if [[ ! -x "$script_dir/run-endpoint.sh" ]]; then
  printf 'error: endpoint runner is not executable\n' >&2
  exit 2
fi

cd "$repo_dir"
exec python3 -m tools.perf_dashboard.server "$@"
