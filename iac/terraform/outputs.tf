output "url" {
  value = module.ecs_environment.url
}

output "alb_dns_name" {
  value = module.ecs_environment.alb_dns_name
}

output "cluster_name" {
  value = module.ecs_environment.cluster_name
}

output "api_service_name" {
  value = module.ecs_environment.api_service_name
}

output "batch_service_name" {
  value = module.ecs_environment.batch_service_name
}

output "vector_bucket_name" {
  value = module.ecs_environment.vector_bucket_name
}

output "batch_operator_user_name" {
  value = module.ecs_environment.batch_operator_user_name
}

output "api_task_family" {
  value = module.ecs_environment.api_task_family
}

output "batch_task_family" {
  value = module.ecs_environment.batch_task_family
}

output "codedeploy_app_name" {
  value = module.ecs_environment.codedeploy_app_name
}

output "codedeploy_deployment_group_name" {
  value = module.ecs_environment.codedeploy_deployment_group_name
}

output "ssm_secret_parameters" {
  value = module.ecs_environment.ssm_secret_parameters
}

output "log_groups" {
  value = module.ecs_environment.log_groups
}

output "dashboard_name" {
  value = module.ecs_environment.dashboard_name
}

output "bastion_public_ip" {
  value = module.ecs_environment.bastion_public_ip
}
