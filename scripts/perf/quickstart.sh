#!/usr/bin/env bash
set -euo pipefail

script_path="${BASH_SOURCE[0]}"
if [[ "$script_path" != */* ]]; then
  script_path="$(command -v "$script_path" || true)"
fi
if [[ -z "$script_path" ]]; then
  echo "error: cannot resolve quickstart.sh path" >&2
  exit 2
fi
script_dir="$(cd "$(dirname "$script_path")" && pwd)"
repo_dir=""

resolve_repo_dir() {
  local candidate
  candidate="$script_dir"
  while [[ "$candidate" != "/" && -n "$candidate" ]]; do
    if [[ -f "$candidate/k6/fixtures/dev.example.json" ]]; then
      repo_dir="$candidate"
      return 0
    fi
    candidate="$(cd "$candidate/.." && pwd)"
  done

  candidate="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || true)"
  if [[ -n "$candidate" ]]; then
    repo_dir="$candidate"
    return 0
  fi

  return 1
}

if ! resolve_repo_dir; then
  echo "error: cannot resolve repository root from ${script_dir}" >&2
  exit 2
fi
fixture_path="${repo_dir}/k6/fixtures/dev.json"
fixture_example="${repo_dir}/k6/fixtures/dev.example.json"
missing=()

for cmd in python3 k6 aws jq session-manager-plugin docker; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    missing+=("$cmd")
  fi
done

if [[ ${#missing[@]} -gt 0 ]]; then
  echo "error: required command not found: ${missing[*]}" >&2
  echo "install: python3, k6, aws, jq, session-manager-plugin, docker" >&2
  exit 2
fi

if [[ ! -f "$fixture_path" ]]; then
  if [[ ! -f "$fixture_example" ]]; then
    echo "error: fixture example not found: $fixture_example" >&2
    exit 2
  fi
  cp "$fixture_example" "$fixture_path"
  chmod 600 "$fixture_path"
  echo "created: $fixture_path (from example). Replace with real dev values before real load runs." >&2
fi

if [[ -z "${JWT_SECRET:-}" ]]; then
  read -r -s -p "JWT_SECRET (dev): " JWT_SECRET
  echo
  export JWT_SECRET
fi

if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  ACCESS_TOKEN="$(python3 "$repo_dir/k6/mint-token.py" 35 2)"
  export ACCESS_TOKEN
fi

if [[ -z "${JWT_SECRET:-}" || -z "${ACCESS_TOKEN:-}" ]]; then
  echo "error: JWT_SECRET and ACCESS_TOKEN are required" >&2
  exit 2
fi

exec "$repo_dir/scripts/perf/dashboard.sh" "$@"
