#!/usr/bin/env bash
# 배치 잡 원격 실행 — ECS Exec 로 배치 컨테이너 안에서 트리거 HTTP 를 호출한다(인바운드 포트 개방 없음).
#   usage: iac/scripts/batch-job.sh run    <env> <jobName>     [aws-profile]
#          iac/scripts/batch-job.sh status <env> <executionId> [aws-profile]
#   exit:  0 접수/조회 성공 · 1 잡/실행 없음(404) · 2 이미 실행 중(409) · 3 전제 미충족(플러그인·태스크) · 그 외 aws cli 오류
set -euo pipefail

usage() {
  sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'
  exit 3
}

CMD="${1:-}"
ENV="${2:-}"
ARG="${3:-}"
PROFILE="${4:-${AWS_PROFILE:-}}"
[[ -z "$PROFILE" && -z "${AWS_ACCESS_KEY_ID:-}" ]] && PROFILE="kbap-${ENV}-batch-operator"
REGION="ap-northeast-2"

[[ "$CMD" == "run" || "$CMD" == "status" ]] || usage
[[ "$ENV" == "dev" || "$ENV" == "prod" ]] || usage
[[ -n "$ARG" ]] || usage

command -v session-manager-plugin >/dev/null 2>&1 || {
  echo "session-manager-plugin 이 없습니다: brew install --cask session-manager-plugin" >&2
  exit 3
}

CLUSTER="kbap-${ENV}-ecs-cluster"
SERVICE="kbap-${ENV}-ecs-batch"
CONTAINER="batch"
BASE="http://localhost:8080/internal/batch"

aws() {
  if [[ -n "$PROFILE" ]]; then command aws --profile "$PROFILE" --region "$REGION" "$@"
  else command aws --region "$REGION" "$@"; fi
}

TASK=$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" --desired-status RUNNING \
  --query 'taskArns[0]' --output text)
if [[ -z "$TASK" || "$TASK" == "None" ]]; then
  echo "실행 중인 배치 태스크가 없습니다(${SERVICE}) — 배치 미기동이거나 Exec 미적용(재배포 필요)" >&2
  exit 3
fi

case "$CMD" in
  run)    CURL="curl -s -w '\n%{http_code}' -X POST '${BASE}/jobs?jobName=${ARG}'" ;;
  status) CURL="curl -s -w '\n%{http_code}' '${BASE}/executions/${ARG}'" ;;
esac

OUT=$(aws ecs execute-command --cluster "$CLUSTER" --task "$TASK" --container "$CONTAINER" \
  --interactive --command "$CURL" | tr -d '\r')

# execute-command 출력은 세션 시작/종료 안내가 앞뒤로 붙는다 — 마지막 3자리 숫자 줄이 HTTP 코드, 그 앞 줄이 본문
CODE=$(printf '%s\n' "$OUT" | grep -E '^[0-9]{3}$' | tail -1 || true)
BODY=$(printf '%s\n' "$OUT" | grep -E '^\{' | tail -1 || true)

[[ -n "$BODY" ]] && printf '%s\n' "$BODY"

case "$CODE" in
  200|202) exit 0 ;;
  404)     exit 1 ;;
  409)     exit 2 ;;
  *)       echo "예상 밖 응답(HTTP ${CODE:-none})" >&2; printf '%s\n' "$OUT" >&2; exit 4 ;;
esac
