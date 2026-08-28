# Contract: prod import 대상 49개와 id 출처 (KB-390)

주소 접두 `module.ecs_environment.` 생략. `P` = `kbap-prod-ecs`. 조회 프로필 `kbap-infra`, 리전 ap-northeast-2. `gen-import-blocks.sh prod` 가 이 표대로 `import.prod.tf` 를 만든다.

| 리소스 주소 | import id | 조회 |
|---|---|---|
| `aws_ecs_cluster.this` | `P-cluster` | 이름 |
| `aws_ecs_service.api` / `.batch` | `P-cluster/P-api` / `P-cluster/P-batch` | 이름 |
| `aws_ecs_task_definition.api` / `.batch` | 최신 ACTIVE 리비전 ARN | `describe-task-definition --task-definition P-api` |
| `aws_cloudwatch_log_group.api` / `.batch` | `/kbap/prod/api` / `/kbap/prod/batch` | 이름 |
| `aws_cloudwatch_dashboard.this` | `P` | 이름 |
| `aws_cloudwatch_metric_alarm.api_5xx` | `P-api-5xx` | 이름 |
| `aws_codedeploy_app.api` | `P-api` | 이름 |
| `aws_codedeploy_deployment_config.canary` | `P-canary-<pct>p-<min>m` | `list-deployment-configs` 에서 접두 매칭 |
| `aws_codedeploy_deployment_group.api` | `P-api:P-api` (app:group) | 이름 |
| `aws_iam_role.instance` / `.task_execution` / `.api_task` / `.batch_task` / `.codedeploy` | `P-container-instance-role` / `P-task-exec-role` / `P-api-task-role` / `P-batch-task-role` / `P-codedeploy-role` | 이름 |
| `aws_iam_instance_profile.instance` | `P-container-instance-profile` | 이름 |
| `aws_iam_role_policy.task_execution_secrets` / `.api_task` / `.batch_task` | `P-task-exec-role:ssm-secrets` / `P-api-task-role:s3-storage` / `P-batch-task-role:sqs-s3` | role:name |
| `aws_iam_role_policy_attachment.instance_ecs` / `.instance_ssm` / `.task_execution_managed` / `.codedeploy_ecs` | `<role>/<policy arn>` | 코드의 policy_arn |
| `aws_iam_user.batch_operator` | `P-batch-operator` | 이름 |
| `aws_iam_user_policy.batch_operator` | `P-batch-operator:batch-remote-run` | user:name |
| `aws_lb.this` | ALB ARN | `describe-load-balancers --names P-alb` |
| `aws_lb_target_group.api["blue"]` / `["green"]` | TG ARN | `--names P-api-blue`, `P-api-green` |
| `aws_lb_listener.http` / `.https` | 리스너 ARN | `describe-listeners --load-balancer-arn` 포트 80/443 |
| `aws_security_group.alb` / `.instance` / `.bastion` | `sg-…` | 이름 태그 `P-alb`/`P-instance`/`P-bastion` (sg.tf 의 name) |
| `aws_vpc_security_group_ingress_rule.*` (9) / `aws_vpc_security_group_egress_rule.*` (3) | `sgr-…` | `describe-security-group-rules --filters Name=group-id` 후 포트·referenced-group·cidr·방향으로 매칭 |
| `aws_launch_template.pool["api"]` / `["batch"]` | `lt-…` | `describe-launch-templates` 이름 접두 `P-api-`/`P-batch-` |
| `aws_autoscaling_group.pool["api"]` / `["batch"]` | `P-api-asg` / `P-batch-asg` | 이름 |
| `aws_instance.bastion` | `i-…` | 태그 Name=`P-bastion`, running |

**제외(신규 생성)**: `aws_cloudwatch_log_group.alloy`, `aws_ecs_task_definition.alloy`, `aws_ecs_service.alloy` → plan 의 "3 to add".

**dev 로 스크립트 검증**: `gen-import-blocks.sh dev` 가 낸 id 를 `terraform state show <addr>` 의 id 와 전수 대조해 일치해야 prod 에 쓴다.
