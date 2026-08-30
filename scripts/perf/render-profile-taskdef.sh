#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 CURRENT_TASKDEF_JSON PROFILE_IMAGE OUTPUT_JSON" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "error: jq is required" >&2
  exit 127
fi

CURRENT_TASKDEF_JSON=$1
PROFILE_IMAGE=$2
OUTPUT_JSON=$3

jq --arg image "$PROFILE_IMAGE" '
  def setenv($name; $value):
    .environment = ((.environment // [])
      | map(select(.name != $name))
      + [{name: $name, value: $value}]);

  .containerDefinitions |= map(
    if .name == "api" then
      .image = $image
      | setenv("SPRING_PROFILES_ACTIVE"; "dev")
      | setenv("SPRING_JPA_SHOW_SQL"; "false")
      | setenv("LOGGING_LEVEL_ROOT"; "WARN")
      | setenv("MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS"; "true")
      | setenv("SERVER_TOMCAT_MBEANREGISTRY_ENABLED"; "true")
    else . end
  )
  | del(.taskDefinitionArn, .revision, .status, .requiresAttributes,
        .compatibilities, .registeredAt, .registeredBy, .deregisteredAt)
' "$CURRENT_TASKDEF_JSON" > "$OUTPUT_JSON"
