resource "aws_elasticache_subnet_group" "this" {
  name       = "kbap-${var.env}-redis-subnets"
  subnet_ids = data.aws_subnets.private.ids
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id      = "kbap-${var.env}-redis"
  engine          = "redis"
  node_type       = var.redis_node_type
  num_cache_nodes = 1
  port            = 6379

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]
}
