#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
FIXTURE="$SCRIPT_DIR/fixtures/api-taskdef.json"
RENDERER="$PERF_DIR/render-profile-taskdef.sh"
PROFILE_IMAGE="123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/kbap-api:profiling-20260831"
OUTPUT=$(mktemp)
trap 'rm -f "$OUTPUT"' EXIT

jq -e . "$FIXTURE" >/dev/null
"$RENDERER" "$FIXTURE" "$PROFILE_IMAGE" "$OUTPUT"

jq -e --arg image "$PROFILE_IMAGE" '
  .containerDefinitions[]
  | select(.name == "api")
  | .image == $image
' "$OUTPUT" >/dev/null

jq -e '
  (.containerDefinitions[] | select(.name == "api") | .environment)
  | (map({key: .name, value: .value}) | from_entries)
  | .SPRING_PROFILES_ACTIVE == "dev"
    and .SPRING_JPA_SHOW_SQL == "false"
    and .LOGGING_LEVEL_ROOT == "WARN"
    and .MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS == "true"
    and .SERVER_TOMCAT_MBEANREGISTRY_ENABLED == "true"
' "$OUTPUT" >/dev/null

jq -e --argjson expected "$(jq '.containerDefinitions[] | select(.name == "api") | {environment: [.environment[] | select(.name == "SPRING_DATASOURCE_URL" or .name == "SPRING_DATA_REDIS_HOST" or .name == "SPRING_DATA_REDIS_PORT")], secrets, healthCheck, logConfiguration}' "$FIXTURE")" '
  .containerDefinitions[]
  | select(.name == "api")
  | {environment: [.environment[] | select(.name == "SPRING_DATASOURCE_URL" or .name == "SPRING_DATA_REDIS_HOST" or .name == "SPRING_DATA_REDIS_PORT")], secrets, healthCheck, logConfiguration} == $expected
' "$OUTPUT" >/dev/null

jq -e '
  (has("taskDefinitionArn") or has("revision") or has("status") or has("requiresAttributes")
    or has("compatibilities") or has("registeredAt") or has("registeredBy") or has("deregisteredAt")) | not
' "$OUTPUT" >/dev/null

jq -e '
  all(.containerDefinitions[] | .environment[]?; .name != "JAVA_TOOL_OPTIONS" or (.value | contains("StartFlightRecording") | not))
' "$OUTPUT" >/dev/null

echo "profile task definition overlay: PASS"
