output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "db_endpoint" {
  value = aws_db_instance.mysql.address
}

output "db_master_secret_arn" {
  description = "RDS 가 Secrets Manager 에 자동 생성한 마스터 비밀번호 — 태스크 정의 DB_PASSWORD 에 채울 값의 출처"
  value       = aws_db_instance.mysql.master_user_secret[0].secret_arn
}

output "redis_endpoint" {
  value = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_name" {
  value = aws_ecs_service.api.name
}
