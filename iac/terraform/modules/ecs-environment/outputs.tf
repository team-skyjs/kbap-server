output "url" {
  value = "https://${local.fqdn}"
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "api_service_name" {
  value = aws_ecs_service.api.name
}

output "batch_service_name" {
  value = aws_ecs_service.batch.name
}

output "batch_operator_user_name" {
  value = aws_iam_user.batch_operator.name
}

output "api_task_family" {
  value = aws_ecs_task_definition.api.family
}

output "vector_bucket_name" {
  value = aws_s3vectors_vector_bucket.foods.vector_bucket_name
}

output "batch_task_family" {
  value = aws_ecs_task_definition.batch.family
}

output "codedeploy_app_name" {
  value = aws_codedeploy_app.api.name
}

output "codedeploy_deployment_group_name" {
  value = aws_codedeploy_deployment_group.api.deployment_group_name
}

output "instance_security_group_id" {
  value = aws_security_group.instance.id
}

output "ssm_secret_parameters" {
  description = "태스크가 참조하는 SSM 파라미터 이름 — apply 전에 값이 등록돼 있어야 태스크가 기동한다"
  value       = [for name in local.secret_names : "${local.ssm_prefix}/${name}"]
}

output "log_groups" {
  value = {
    api   = aws_cloudwatch_log_group.api.name
    batch = aws_cloudwatch_log_group.batch.name
  }
}

output "dashboard_name" {
  value = aws_cloudwatch_dashboard.this.dashboard_name
}

output "bastion_public_ip" {
  value = aws_instance.bastion.public_ip
}

output "alloy_service_name" {
  value = aws_ecs_service.alloy.name
}
