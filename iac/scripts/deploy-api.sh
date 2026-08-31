#!/usr/bin/env bash
# api 카나리 배포: 현재 태스크 정의에서 이미지만 바꿔 새 리비전을 등록하고 CodeDeploy 배포를 만든다.
#   usage: iac/scripts/deploy-api.sh <env> <image-tag> [aws-profile]
#   예:    iac/scripts/deploy-api.sh dev f5fd3c6e94e40bc92b4de93d0dbbc5f4ba5b5795
# 진행: 20% 트래픽 15분 → 100% → 구버전 15분 유지 후 종료 (Terraform 의 canary_* 변수 기준)
set -euo pipefail

ENV="${1:?env (dev|prod)}"
TAG="${2:?image tag}"
PROFILE="${3:-${AWS_PROFILE:-kbap-prod-deployer}}"
REGION="ap-northeast-2"
NAME="kbap-${ENV}-ecs"
CLUSTER="${NAME}-cluster"
SERVICE="${NAME}-api"
FAMILY="${NAME}-api"
APP="${NAME}-api"
GROUP="${NAME}-api"
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

appspec=$(jq -n --arg arn "$new_arn" --arg name "$CONTAINER" '{
  version: 0.0,
  Resources: [{ TargetService: { Type: "AWS::ECS::Service",
    Properties: { TaskDefinition: $arn, LoadBalancerInfo: { ContainerName: $name, ContainerPort: 8080 } } } }]
}')

deployment_id=$(aws deploy create-deployment \
  --application-name "$APP" --deployment-group-name "$GROUP" \
  --revision "{\"revisionType\":\"AppSpecContent\",\"appSpecContent\":{\"content\":$(jq -Rs . <<<"$appspec")}}" \
  --description "api ${TAG}" \
  --query 'deploymentId' --output text)

echo "deployment: $deployment_id"
echo "watch:      aws deploy get-deployment --deployment-id $deployment_id --query 'deploymentInfo.[status,deploymentOverview]'"
echo "rollback:   aws deploy stop-deployment --deployment-id $deployment_id --auto-rollback-enabled"
