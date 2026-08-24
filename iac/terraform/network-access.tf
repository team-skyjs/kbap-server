resource "aws_security_group_rule" "eks_nodes_to_rds_mysql" {
  type              = "ingress"
  from_port         = 3306
  to_port           = 3306
  protocol          = "tcp"
  description       = "EKS node SG to RDS mysql 3306"
  security_group_id = var.rds_security_group_id

  source_security_group_id = module.eks_environment.node_security_group_id
}

resource "aws_security_group_rule" "eks_nodes_to_redis" {
  count = var.redis_security_group_id == null ? 0 : 1

  type              = "ingress"
  from_port         = 6379
  to_port           = 6379
  protocol          = "tcp"
  description       = "EKS node SG to Redis 6379"
  security_group_id = var.redis_security_group_id

  source_security_group_id = module.eks_environment.node_security_group_id
}
