#!/usr/bin/env bash
set -euo pipefail

image="${PROFILE_IMAGE:-kbap-api-profile:local}"
docker run --rm --entrypoint sh "$image" -c '
  command -v java
  command -v jcmd
  command -v jfr
  command -v aws
  command -v curl
  test -f /app/kbap-profile.jfc
'

docker run --rm --entrypoint sh "$image" -c '
  jwebserver -p 18080 >/tmp/jwebserver.log 2>&1 &
  pid=$!
  sleep 1
  jcmd "$pid" JFR.start name=profile_smoke settings=/app/kbap-profile.jfc filename=/tmp/profile-smoke.jfr maxsize=32m
  sleep 2
  jcmd "$pid" JFR.stop name=profile_smoke
  jfr summary /tmp/profile-smoke.jfr | grep "Version:"
  test -s /tmp/profile-smoke.jfr
  kill "$pid"
'
