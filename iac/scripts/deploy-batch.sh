#!/usr/bin/env bash
# batch 배포: 새 이미지로 태스크 정의 리비전 등록 후 서비스 롤링 교체 (단일 인스턴스 — 구 태스크 종료 → 신 태스크 기동).
#   usage: iac/scripts/deploy-batch.sh <env> <image-tag> [aws-profile]
set -euo pipefail

ENV="${1:?env (dev|prod)}"
TAG="${2:?image tag}"
PROFILE="${3:-${AWS_PROFILE:-kbap-prod-deployer}}"
REGION="ap-northeast-2"
NAME="kbap-${ENV}-ecs"
CLUSTER="${NAME}-cluster"
SERVICE="${NAME}-batch"
FAMILY="${NAME}-batch"
REPO="118178010621.dkr.ecr.${REGION}.amazonaws.com/kbap/batch"

aws() { command aws --profile "$PROFILE" --region "$REGION" "$@"; }

current=$(aws ecs describe-task-definition --task-definition "$FAMILY" --query 'taskDefinition')
new_def=$(jq --arg image "${REPO}:${TAG}" '
  .containerDefinitions |= map(if .name == "batch" then .image = $image else . end)
  | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
        .registeredAt, .registeredBy, .deregisteredAt)
' <<<"$current")

new_arn=$(aws ecs register-task-definition --cli-input-json "$new_def" \
  --query 'taskDefinition.taskDefinitionArn' --output text)
echo "registered: $new_arn"

aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$new_arn" >/dev/null
echo "rolling...  aws ecs wait services-stable --cluster $CLUSTER --services $SERVICE"
aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
echo "done"
