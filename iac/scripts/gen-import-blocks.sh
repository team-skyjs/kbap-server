#!/usr/bin/env bash
# prod(또는 dev) ECS 환경의 실리소스를 조회해 terraform `import` 블록 파일과 tfvars 초안을 만든다 (KB-390).
#   usage: iac/scripts/gen-import-blocks.sh <dev|prod> [--check] [aws-profile]
#   출력: iac/terraform/import.<env>.tf  +  iac/terraform/<env>.tfvars.generated   (둘 다 gitignore)
#   --check: 파일을 쓰지 않고, 현재 선택된 terraform workspace 의 state 와 id 를 전수 대조한다(dev 로 스크립트 신뢰 검증용).
# 조회 실패·복수 매칭은 즉시 exit 1 — 부분 파일을 남기지 않는다. 대상 목록은 specs/kb-390 contracts/import-ids.md.
set -euo pipefail

ENV="${1:?env (dev|prod)}"; shift || true
CHECK=0; PROFILE="kbap-infra"
for a in "$@"; do case "$a" in --check) CHECK=1;; *) PROFILE="$a";; esac; done
REGION="ap-northeast-2"
P="kbap-${ENV}-ecs"
TF_DIR="$(cd "$(dirname "$0")/../terraform" && pwd)"
TF="${TERRAFORM_BIN:-$(command -v terraform || echo /opt/homebrew/bin/terraform)}"

aws() { command aws --profile "$PROFILE" --region "$REGION" --output text "$@"; }
fail() { echo "ERROR: $*" >&2; exit 1; }
one() { # $1=value $2=label — 정확히 한 줄(비어 있지 않음)만 허용
  local v="$1" label="$2"
  [ -n "$v" ] && [ "$(printf '%s\n' "$v" | wc -l | tr -d ' ')" -eq 1 ] || fail "$label: expected exactly one match, got: [$v]"
  printf '%s' "$v"
}
declare -a ADDRS IDS
add() { ADDRS+=("$1"); IDS+=("$2"); echo "  $1 = $2" >&2; }

echo "== $ENV ($P) via profile $PROFILE" >&2

# --- ECS ---
CLUSTER=$(one "$(aws ecs describe-clusters --clusters "$P-cluster" --query 'clusters[?status==`ACTIVE`].clusterName')" cluster)
add aws_ecs_cluster.this "$CLUSTER"
for svc in api batch; do
  one "$(aws ecs describe-services --cluster "$CLUSTER" --services "$P-$svc" --query 'services[?status==`ACTIVE`].serviceName')" "service $svc" >/dev/null
  add "aws_ecs_service.$svc" "$CLUSTER/$P-$svc"
  add "aws_ecs_task_definition.$svc" "$(one "$(aws ecs describe-task-definition --task-definition "$P-$svc" --query taskDefinition.taskDefinitionArn)" "taskdef $svc")"
done

# Alloy DAEMON(KB-381)은 이미 적용된 환경(dev)에서만 존재 — 있으면 import, 없으면(prod 첫 적용) plan 의 "3 to add" 로 생성된다
if [ -n "$(aws ecs describe-services --cluster "$CLUSTER" --services "$P-alloy" --query 'services[?status==`ACTIVE`].serviceName')" ]; then
  add aws_ecs_service.alloy "$CLUSTER/$P-alloy"
  add aws_ecs_task_definition.alloy "$(one "$(aws ecs describe-task-definition --task-definition "$P-alloy" --query taskDefinition.taskDefinitionArn)" "taskdef alloy")"
  add aws_cloudwatch_log_group.alloy "/kbap/$ENV/alloy"
else
  echo "  (alloy 없음 — plan 에서 3 to add 로 생성됨)" >&2
fi

# --- CloudWatch ---
for lg in api batch; do
  one "$(aws logs describe-log-groups --log-group-name-prefix "/kbap/$ENV/$lg" --query "logGroups[?logGroupName=='/kbap/$ENV/$lg'].logGroupName")" "log group $lg" >/dev/null
  add "aws_cloudwatch_log_group.$lg" "/kbap/$ENV/$lg"
done
one "$(aws cloudwatch get-dashboard --dashboard-name "$P" --query DashboardName)" dashboard >/dev/null
add aws_cloudwatch_dashboard.this "$P"
one "$(aws cloudwatch describe-alarms --alarm-names "$P-api-5xx" --query 'MetricAlarms[].AlarmName')" alarm >/dev/null
add aws_cloudwatch_metric_alarm.api_5xx "$P-api-5xx"

# --- CodeDeploy ---
one "$(aws deploy get-application --application-name "$P-api" --query application.applicationName)" "codedeploy app" >/dev/null
add aws_codedeploy_app.api "$P-api"
CFG=$(one "$(aws deploy list-deployment-configs --query "deploymentConfigsList[?starts_with(@, '$P-canary-')]" | tr '\t' '\n')" "deployment config")
add aws_codedeploy_deployment_config.canary "$CFG"
DG=$(aws deploy get-deployment-group --application-name "$P-api" --deployment-group-name "$P-api" \
  --query 'deploymentGroupInfo.[deploymentGroupName,blueGreenDeploymentConfiguration.terminateBlueInstancesOnDeploymentSuccess.terminationWaitTimeInMinutes]')
add aws_codedeploy_deployment_group.api "$P-api:$P-api"
BLUE_WAIT=$(printf '%s' "$DG" | awk '{print $2}')

# --- IAM ---
# macOS 기본 bash 3.2 — 연관 배열 없이 함수로
role_name() { case "$1" in instance) echo "$P-container-instance-role";; task_execution) echo "$P-task-exec-role";; api_task) echo "$P-api-task-role";; batch_task) echo "$P-batch-task-role";; codedeploy) echo "$P-codedeploy-role";; esac; }
for k in instance task_execution api_task batch_task codedeploy; do
  one "$(aws iam get-role --role-name "$(role_name $k)" --query Role.RoleName)" "role $k" >/dev/null
  add "aws_iam_role.$k" "$(role_name $k)"
done
one "$(aws iam get-instance-profile --instance-profile-name "$P-container-instance-profile" --query InstanceProfile.InstanceProfileName)" "instance profile" >/dev/null
add aws_iam_instance_profile.instance "$P-container-instance-profile"
add aws_iam_role_policy.task_execution_secrets "$(role_name task_execution):ssm-secrets"
add aws_iam_role_policy.api_task "$(role_name api_task):s3-storage"
add aws_iam_role_policy.batch_task "$(role_name batch_task):sqs-s3"
add aws_iam_role_policy_attachment.instance_ecs "$(role_name instance)/arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
add aws_iam_role_policy_attachment.instance_ssm "$(role_name instance)/arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
add aws_iam_role_policy_attachment.task_execution_managed "$(role_name task_execution)/arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
add aws_iam_role_policy_attachment.codedeploy_ecs "$(role_name codedeploy)/arn:aws:iam::aws:policy/AWSCodeDeployRoleForECS"
# 배치 운영자 IAM 사용자(KB-374)는 그 이후에 만든 환경에만 있다 — 없으면 plan 의 "to add" 로 생성된다
if [ -n "$(aws iam get-user --user-name "$P-batch-operator" --query User.UserName 2>/dev/null)" ]; then
  add aws_iam_user.batch_operator "$P-batch-operator"
  add aws_iam_user_policy.batch_operator "$P-batch-operator:batch-remote-run"
else
  echo "  (batch-operator IAM 사용자 없음 — plan 에서 2 to add 로 생성됨)" >&2
fi

# --- ALB ---
ALB=$(one "$(aws elbv2 describe-load-balancers --names "$P-alb" --query 'LoadBalancers[0].[LoadBalancerArn,VpcId]' | tr '\t' '\n' | head -1)" alb)
VPC_ID=$(aws elbv2 describe-load-balancers --names "$P-alb" --query 'LoadBalancers[0].VpcId')
add aws_lb.this "$ALB"
for color in blue green; do
  add "aws_lb_target_group.api[\"$color\"]" "$(one "$(aws elbv2 describe-target-groups --names "$P-api-$color" --query 'TargetGroups[0].TargetGroupArn')" "tg $color")"
done
add aws_lb_listener.http  "$(one "$(aws elbv2 describe-listeners --load-balancer-arn "$ALB" --query 'Listeners[?Port==`80`].ListenerArn')" "listener 80")"
add aws_lb_listener.https "$(one "$(aws elbv2 describe-listeners --load-balancer-arn "$ALB" --query 'Listeners[?Port==`443`].ListenerArn')" "listener 443")"

# --- Security groups ---
sg_by_name() { one "$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$1" "Name=vpc-id,Values=$VPC_ID" --query 'SecurityGroups[].GroupId')" "sg $1"; }
SG_ALB=$(sg_by_name "$P-alb"); SG_INST=$(sg_by_name "$P-instance"); SG_BASTION=$(sg_by_name "$P-bastion")
add aws_security_group.alb "$SG_ALB"; add aws_security_group.instance "$SG_INST"; add aws_security_group.bastion "$SG_BASTION"
# rule <sg> <egress:true|false> <proto> <from> <to> <cidr|ref-sg>  → sgr-id
rule() {
  local sg="$1" eg="$2" proto="$3" from="$4" to="$5" src="$6" q
  if [[ "$src" == sg-* ]]; then q="ReferencedGroupInfo.GroupId=='$src'"; else q="CidrIpv4=='$src'"; fi
  if [ "$from" = "-" ]; then
    one "$(aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$sg" --query "SecurityGroupRules[?IsEgress==\`$eg\` && IpProtocol=='$proto' && $q].SecurityGroupRuleId")" "rule $sg $eg $proto $src"
  else
    one "$(aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$sg" --query "SecurityGroupRules[?IsEgress==\`$eg\` && IpProtocol=='$proto' && FromPort==\`$from\` && ToPort==\`$to\` && $q].SecurityGroupRuleId")" "rule $sg $eg $proto $from-$to $src"
  fi
}
add aws_vpc_security_group_ingress_rule.alb_http  "$(rule "$SG_ALB" false tcp 80 80 0.0.0.0/0)"
add aws_vpc_security_group_ingress_rule.alb_https "$(rule "$SG_ALB" false tcp 443 443 0.0.0.0/0)"
add aws_vpc_security_group_egress_rule.alb_all    "$(rule "$SG_ALB" true -1 - - 0.0.0.0/0)"
add aws_vpc_security_group_ingress_rule.instance_from_alb   "$(rule "$SG_INST" false tcp 32768 65535 "$SG_ALB")"
add aws_vpc_security_group_ingress_rule.instance_batch_http "$(rule "$SG_INST" false tcp 8080 8080 "$SG_INST")"
add aws_vpc_security_group_egress_rule.instance_all         "$(rule "$SG_INST" true -1 - - 0.0.0.0/0)"
ADMIN_RULE=$(aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$SG_BASTION" --query 'SecurityGroupRules[?IsEgress==`false` && FromPort==`22`].[SecurityGroupRuleId,CidrIpv4]')
add aws_vpc_security_group_ingress_rule.bastion_ssh "$(one "$(printf '%s' "$ADMIN_RULE" | awk '{print $1}')" "bastion ssh rule")"
ADMIN_CIDR=$(printf '%s' "$ADMIN_RULE" | awk '{print $2}')
add aws_vpc_security_group_egress_rule.bastion_all "$(rule "$SG_BASTION" true -1 - - 0.0.0.0/0)"
# RDS/Redis SG 는 Terraform 소유가 아니라 이름을 모른다 — instance/bastion SG 를 참조하는 3306/6379 인바운드 규칙에서 역으로 찾는다
ext_rule() { # $1=port $2=referenced sg → "sgr-id group-id"
  one "$(aws ec2 describe-security-group-rules --query "SecurityGroupRules[?IsEgress==\`false\` && FromPort==\`$1\` && ReferencedGroupInfo.GroupId=='$2'].[SecurityGroupRuleId,GroupId]" | tr '\t' ' ')" "external rule port $1 ref $2"
}
R=$(ext_rule 3306 "$SG_INST");    add aws_vpc_security_group_ingress_rule.rds_from_instance "${R%% *}";   RDS_SG="${R##* }"
R=$(ext_rule 6379 "$SG_INST");    add aws_vpc_security_group_ingress_rule.redis_from_instance "${R%% *}"; REDIS_SG="${R##* }"
R=$(ext_rule 3306 "$SG_BASTION"); add aws_vpc_security_group_ingress_rule.rds_from_bastion "${R%% *}"
R=$(ext_rule 6379 "$SG_BASTION"); add aws_vpc_security_group_ingress_rule.redis_from_bastion "${R%% *}"

# --- Compute ---
for pool in api batch; do
  add "aws_launch_template.pool[\"$pool\"]" "$(one "$(aws ec2 describe-launch-templates --filters "Name=launch-template-name,Values=$P-$pool-*" --query 'LaunchTemplates[].LaunchTemplateId')" "launch template $pool")"
  one "$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$P-$pool-asg" --query 'AutoScalingGroups[].AutoScalingGroupName')" "asg $pool" >/dev/null
  add "aws_autoscaling_group.pool[\"$pool\"]" "$P-$pool-asg"
done
BASTION=$(aws ec2 describe-instances --filters "Name=tag:Name,Values=$P-bastion" "Name=instance-state-name,Values=running,stopped" --query 'Reservations[].Instances[].[InstanceId,KeyName]')
add aws_instance.bastion "$(one "$(printf '%s' "$BASTION" | awk '{print $1}')" bastion)"
BASTION_KEY=$(printf '%s' "$BASTION" | awk '{print $2}')

echo "== ${#ADDRS[@]} resources" >&2

# --- --check: 현재 workspace 의 state 와 대조 ---
if [ "$CHECK" = 1 ]; then
  cd "$TF_DIR"; bad=0
  for i in "${!ADDRS[@]}"; do
    sid=$("$TF" state show -no-color "module.ecs_environment.${ADDRS[$i]}" 2>/dev/null | awk -F'"' '/^    id += /{print $2; exit}' || true)
    if [ "$sid" != "${IDS[$i]}" ]; then echo "MISMATCH ${ADDRS[$i]}: state=[$sid] aws=[${IDS[$i]}]"; bad=$((bad+1)); fi
  done
  echo "check: ${#ADDRS[@]} compared, $bad mismatch"; [ "$bad" -eq 0 ]
  exit
fi

# --- import.<env>.tf ---
OUT="$TF_DIR/import.$ENV.tf"
{
  echo "# generated by iac/scripts/gen-import-blocks.sh $ENV — apply 성공 후 삭제 (gitignore)"
  for i in "${!ADDRS[@]}"; do
    printf 'import {\n  to = module.ecs_environment.%s\n  id = "%s"\n}\n' "${ADDRS[$i]}" "${IDS[$i]}"
  done
} > "$OUT"
echo "wrote $OUT" >&2

# --- <env>.tfvars.generated (검토 후 <env>.tfvars 로) ---
envval() { aws ecs describe-task-definition --task-definition "$P-$1" --query "taskDefinition.containerDefinitions[0].environment[?name=='$2'].value"; }
API_IMG=$(aws ecs describe-task-definition --task-definition "$P-api" --query 'taskDefinition.containerDefinitions[0].image')
BATCH_IMG=$(aws ecs describe-task-definition --task-definition "$P-batch" --query 'taskDefinition.containerDefinitions[0].image')
VPC_NAME=$(aws ec2 describe-vpcs --vpc-ids "$VPC_ID" --query 'Vpcs[0].Tags[?Key==`Name`].Value')
QUEUE_URL=$(envval batch FOOD_CONTENT_QUEUE_URL)
API_ASG=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$P-api-asg" --query 'AutoScalingGroups[0].DesiredCapacity')
BATCH_ASG=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$P-batch-asg" --query 'AutoScalingGroups[0].DesiredCapacity')
API_DESIRED=$(aws ecs describe-services --cluster "$CLUSTER" --services "$P-api" --query 'services[0].desiredCount')
BATCH_DESIRED=$(aws ecs describe-services --cluster "$CLUSTER" --services "$P-batch" --query 'services[0].desiredCount')
RETENTION=$(aws logs describe-log-groups --log-group-name-prefix "/kbap/$ENV/api" --query "logGroups[?logGroupName=='/kbap/$ENV/api'].retentionInDays")
CANARY_PCT=$(printf '%s' "$CFG" | sed -E 's/.*-canary-([0-9]+)p-([0-9]+)m/\1/'); CANARY_MIN=$(printf '%s' "$CFG" | sed -E 's/.*-canary-([0-9]+)p-([0-9]+)m/\2/')
GEN="$TF_DIR/$ENV.tfvars.generated"
cat > "$GEN" <<TFV
# generated by gen-import-blocks.sh $ENV — AWS 실값 기준 초안. 검토 후 $ENV.tfvars 로 복사 (gitignore, 커밋 금지)
env       = "$ENV"
vpc_name  = "$VPC_NAME"
subdomain = "$ENV-ecs"

spring_profile     = "$(envval api SPRING_PROFILES_ACTIVE)"
storage_key_prefix = "$(envval api STORAGE_KEY_PREFIX)"
storage_bucket     = "$(envval api STORAGE_BUCKET)"
cdn_base_url       = "$(envval api CDN_BASE_URL)"
image_public_base_url = "$(envval api IMAGE_PUBLIC_BASE_URL)"
food_content_queue_name = "${QUEUE_URL##*/}"

api_image   = "$API_IMG"
batch_image = "$BATCH_IMG"

db_url                  = "$(envval api DB_URL)"
db_username             = "$(envval api DB_USERNAME)"
redis_host              = "$(envval api REDIS_HOST)"
rds_security_group_id   = "$RDS_SG"
redis_security_group_id = "$REDIS_SG"

api_instance_count   = $API_ASG
batch_instance_count = $BATCH_ASG
api_desired_count    = $API_DESIRED
batch_desired_count  = $BATCH_DESIRED
log_retention_days   = ${RETENTION:-7}
canary_percentage       = $CANARY_PCT
canary_interval_minutes = $CANARY_MIN
blue_termination_wait_minutes = ${BLUE_WAIT:-15}

admin_cidr       = "$ADMIN_CIDR"
bastion_key_name = "$BASTION_KEY"

home_prometheus_remote_write_url = "https://prom-write.handev.site/api/v1/write"
blocked_path_patterns = $([ "$ENV" = prod ] && echo '["*actuator*", "*swagger*", "*api-docs*"]' || echo '["*actuator*"]')
TFV
echo "wrote $GEN — 예시 파일(dev/prod.tfvars.example)과 대조 후 $ENV.tfvars 로" >&2
