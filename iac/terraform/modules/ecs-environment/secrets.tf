# 시크릿 값은 Terraform 이 소유하지 않는다 — 자리만 만들고 실값은 CLI 로 넣는다:
#   aws ssm put-parameter --name /kbap/<env>/DB_PASSWORD --type SecureString --value '...' --overwrite
locals {
  secret_names = toset(concat(var.api_secret_names, var.batch_secret_names))
}

resource "aws_ssm_parameter" "secret" {
  for_each = local.secret_names

  name  = "${local.ssm_prefix}/${each.key}"
  type  = "SecureString"
  value = "CHANGE_ME"
  tags  = local.common_tags

  lifecycle {
    ignore_changes = [value]
  }
}
