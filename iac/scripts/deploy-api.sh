#!/usr/bin/env bash
# api 롤링 배포: 현재 태스크 정의에서 이미지만 바꿔 새 리비전을 등록하고 서비스를 교체한다.
#   usage: iac/scripts/deploy-api.sh <env> <image-tag> [aws-profile]
#   예:    iac/scripts/deploy-api.sh dev f5fd3c6e94e40bc92b4de93d0dbbc5f4ba5b5795
# 진행: 인스턴스 한 대씩 구 태스크 종료 → 같은 자리에 신 태스크 기동(min 50% / max 100%). 기동 실패는 서킷브레이커가 롤백.
set -euo pipefail

ENV="${1:?env (dev|prod)}"
TAG="${2:?image tag}"
PROFILE="${3:-${AWS_PROFILE:-kbap-prod-deployer}}"
REGION="ap-northeast-2"
NAME="kbap-${ENV}-ecs"
CLUSTER="${NAME}-cluster"
SERVICE="${NAME}-api"
FAMILY="${NAME}-api"
CONTAINER="api"
REPO="118178010621.dkr.ecr.${REGION}.amazonaws.com/kbap/api"

aws() { command aws --profile "$PROFILE" --region "$REGION" "$@"; }

current=$(aws ecs describe-task-definition --task-definition "$FAMILY" --query 'taskDefinition')

new_def=$(jq --arg image "${REPO}:${TAG}" --arg name "$CONTAINER" '
  .containerDefinitions |= map(if .name == $name then .image = $image else . end)
  | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
        .registeredAt, .registeredBy, .deregisteredAt)
' <<<"$current")

new_arn=$(aws ecs register-task-definition --cli-input-json "$new_def" \
  --query 'taskDefinition.taskDefinitionArn' --output text)
echo "registered: $new_arn"

aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$new_arn" >/dev/null
echo "rolling...  aws ecs describe-services --cluster $CLUSTER --services $SERVICE --query 'services[0].deployments[].[id,status,rolloutState]'"
aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
echo "done"
