# 시크릿(SSM SecureString)은 Terraform 이 만들지도 읽지도 않는다 — 이름 규칙(/kbap/<env>/<NAME>)만 공유한다.
# 태스크 정의는 ARN 문자열로 참조하고 ECS 가 기동 시점에 값을 가져온다.
# 등록: aws ssm put-parameter --name /kbap/<env>/DB_PASSWORD --type SecureString --value '...' --overwrite
locals {
  secret_names = toset(concat(var.api_secret_names, var.batch_secret_names, var.alloy_secret_names)) # 실행 롤 정책은 ${ssm_prefix}/* 라 IAM 변경 없음

  secret_arns = {
    for name in local.secret_names :
    name => "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}/${name}"
  }
}
