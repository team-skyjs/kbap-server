#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
launcher="$repo_dir/scripts/perf/dashboard.sh"
temp_dir="$(mktemp -d)"
fake_bin="$temp_dir/bin"
calls="$temp_dir/calls"
fixture="$temp_dir/dev.json"
real_python="$(command -v python3)"
jwt_secret=dashboard-test-secret

cleanup() {
  rm -rf "$temp_dir"
}
trap cleanup EXIT

mkdir -p "$fake_bin"
printf '{}\n' >"$fixture"
for command in k6 aws jq session-manager-plugin docker; do
  printf '#!/usr/bin/env bash\nexit 0\n' >"$fake_bin/$command"
  chmod +x "$fake_bin/$command"
done
cat >"$fake_bin/aws" <<'AWS'
#!/usr/bin/env bash
if [[ " $* " == *" sts get-caller-identity "* ]]; then
  printf '%s\n' '118178010621'
  exit 0
fi
exit 64
AWS
cat >"$fake_bin/python3" <<'PYTHON'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-m" && "${2:-}" == "tools.perf_dashboard.server" ]]; then
  printf 'dashboard-server\n' >>"$FAKE_CALLS"
  exit 0
fi
exec "$REAL_PYTHON" "$@"
PYTHON
chmod +x "$fake_bin/python3"

access_token="$({ JWT_SECRET="$jwt_secret" "$real_python" "$repo_dir/k6/mint-token.py" 35 2; })"

run_launcher() {
  PATH="$fake_bin:$PATH" REAL_PYTHON="$real_python" FAKE_CALLS="$calls" \
    FIXTURE_PATH="$fixture" JWT_SECRET="$jwt_secret" ACCESS_TOKEN="$access_token" \
    "$@"
}

: >"$calls"
run_launcher "$launcher"
grep -q '^dashboard-server$' "$calls"

: >"$calls"
set +e
run_launcher env ACCESS_TOKEN=invalid "$launcher" >"$temp_dir/bad-token.out" 2>"$temp_dir/bad-token.err"
bad_token_status=$?
set -e
test "$bad_token_status" -ne 0
test ! -s "$calls"

: >"$calls"
set +e
run_launcher env AWS_PROFILE=prod "$launcher" >"$temp_dir/prod.out" 2>"$temp_dir/prod.err"
prod_status=$?
set -e
test "$prod_status" -ne 0
test ! -s "$calls"

: >"$calls"
set +e
run_launcher env JWT_SECRET= ACCESS_TOKEN= "$launcher" >"$temp_dir/missing.out" 2>"$temp_dir/missing.err"
missing_status=$?
set -e
test "$missing_status" -ne 0
grep -q 'k6/mint-token.py 35 2' "$temp_dir/missing.err"
test ! -s "$calls"

mv "$fake_bin/session-manager-plugin" "$fake_bin/session-manager-plugin.missing"
: >"$calls"
set +e
run_launcher "$launcher" >"$temp_dir/plugin.out" 2>"$temp_dir/plugin.err"
plugin_status=$?
set -e
test "$plugin_status" -ne 0
grep -q 'session-manager-plugin' "$temp_dir/plugin.err"
test ! -s "$calls"

printf '%s\n' 'performance dashboard launcher: PASS'
