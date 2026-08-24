output "cluster_name" {
  value = module.eks_environment.cluster_name
}

output "api_target_group_arn" {
  value = try(aws_lb_target_group.api[0].arn, null)
}

output "api_public_url" {
  value = var.api_public_hostname == null ? null : "https://${var.api_public_hostname}"
}

output "aws_load_balancer_controller_role_arn" {
  value = try(aws_iam_role.aws_load_balancer_controller[0].arn, null)
}

output "cluster_endpoint" {
  value = module.eks_environment.cluster_endpoint
}

output "cluster_certificate_authority_data" {
  value = module.eks_environment.cluster_certificate_authority_data
}

output "node_security_group_id" {
  value = module.eks_environment.node_security_group_id
}

output "api_pod_identity_role_name" {
  value = aws_iam_role.api_pod_identity.name
}

output "api_pod_identity_role_arn" {
  value = aws_iam_role.api_pod_identity.arn
}

output "api_pod_identity_policy_arn" {
  value = aws_iam_policy.api_pod_identity_s3.arn
}

output "api_pod_identity_association_arn" {
  value = aws_eks_pod_identity_association.api.association_arn
}

output "cloudwatch_application_log_group_name" {
  value = aws_cloudwatch_log_group.eks_application.name
}

output "cloudwatch_dataplane_log_group_name" {
  value = aws_cloudwatch_log_group.eks_dataplane.name
}

output "cloudwatch_host_log_group_name" {
  value = aws_cloudwatch_log_group.eks_host.name
}

output "cloudwatch_otel_application_log_group_name" {
  value = aws_cloudwatch_log_group.eks_otel_application.name
}

output "cloudwatch_otel_host_log_group_name" {
  value = aws_cloudwatch_log_group.eks_otel_host.name
}

output "cloudwatch_agent_role_arn" {
  value = aws_iam_role.cloudwatch_agent.arn
}

output "cloudwatch_observability_addon_version" {
  value = aws_eks_addon.cloudwatch_observability.addon_version
}
