resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb"
  description = "public ALB for ${local.fqdn}"
  vpc_id      = data.aws_vpc.this.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-alb" })
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "instance" {
  name        = "${local.name_prefix}-instance"
  description = "ECS container instances (api + batch)"
  vpc_id      = data.aws_vpc.this.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-instance" })
}

# bridge 모드 동적 호스트 포트 — ALB 만 접근
resource "aws_vpc_security_group_ingress_rule" "instance_from_alb" {
  security_group_id            = aws_security_group.instance.id
  ip_protocol                  = "tcp"
  from_port                    = 32768
  to_port                      = 65535
  referenced_security_group_id = aws_security_group.alb.id
}

# 배치 잡 트리거 포트 — 같은 클러스터 인스턴스(api)에서만
resource "aws_vpc_security_group_ingress_rule" "instance_batch_http" {
  security_group_id            = aws_security_group.instance.id
  ip_protocol                  = "tcp"
  from_port                    = var.batch_http_port
  to_port                      = var.batch_http_port
  referenced_security_group_id = aws_security_group.instance.id
}

resource "aws_vpc_security_group_egress_rule" "instance_all" {
  security_group_id = aws_security_group.instance.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# 기존 RDS·Redis 보안그룹에 인바운드만 추가 (그 SG 자체는 Terraform 소유가 아님)
resource "aws_vpc_security_group_ingress_rule" "rds_from_instance" {
  security_group_id            = var.rds_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.instance.id
  description                  = "${local.name_prefix} container instances"
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_instance" {
  security_group_id            = var.redis_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.instance.id
  description                  = "${local.name_prefix} container instances"
}
