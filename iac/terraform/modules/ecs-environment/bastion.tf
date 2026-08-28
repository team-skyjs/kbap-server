# DB 접속용 점프 호스트 — DataGrip 등에서 SSH 터널로 RDS(프라이빗)에 붙는 유일한 사람 경로.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_security_group" "bastion" {
  name        = "${local.name_prefix}-bastion"
  description = "SSH jump host for RDS tunneling"
  vpc_id      = data.aws_vpc.this.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-bastion" })
}

resource "aws_vpc_security_group_ingress_rule" "bastion_ssh" {
  security_group_id = aws_security_group.bastion.id
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = var.admin_cidr
  description       = "SSH from admin"
}

resource "aws_vpc_security_group_egress_rule" "bastion_all" {
  security_group_id = aws_security_group.bastion.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# RDS·Redis 는 bastion 에서만 사람이 접근 (앱은 컨테이너 인스턴스 SG 로 별도 허용 — sg.tf)
resource "aws_vpc_security_group_ingress_rule" "rds_from_bastion" {
  security_group_id            = var.rds_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.bastion.id
  description                  = "${local.name_prefix} bastion"
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_bastion" {
  security_group_id            = var.redis_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.bastion.id
  description                  = "${local.name_prefix} bastion"
}

resource "aws_instance" "bastion" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.bastion_instance_type
  subnet_id                   = data.aws_subnets.public.ids[0]
  vpc_security_group_ids      = [aws_security_group.bastion.id]
  associate_public_ip_address = true
  key_name                    = var.bastion_key_name
  iam_instance_profile        = aws_iam_instance_profile.instance.name

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 8
    volume_type = "gp3"
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-bastion" })

  # AMI 는 최초 생성 시점의 값으로 고정한다 — SSM "latest" 파라미터가 갱신될 때마다 plan 에 드리프트가 잡히고,
  # 적용하면 bastion 이 교체된다(SSH 호스트키·공인 IP 변경). 이미지 갱신은 인스턴스 교체로 사람이 결정한다. (KB-390 import 에서 확인)
  lifecycle {
    ignore_changes = [ami]
  }
}
