module "eks_environment" {
  source = "./modules/eks-environment"

  cluster_name             = var.cluster_name
  region                   = var.region
  profile                  = var.aws_profile
  vpc_name                 = var.vpc_name
  admin_cidr               = var.admin_cidr
  kubernetes_version       = var.kubernetes_version
  eks_public_subnet_a_cidr = var.eks_public_subnet_a_cidr
  eks_public_subnet_b_cidr = var.eks_public_subnet_b_cidr

  api_node_instance_types = var.api_node_instance_types
  ops_node_instance_types = var.ops_node_instance_types
  api_node_capacity_type  = var.api_node_capacity_type
  ops_node_capacity_type  = var.ops_node_capacity_type
  node_disk_size          = var.node_disk_size

  cloudwatch_log_retention_days = var.cloudwatch_log_retention_days

  addon_versions = var.addon_versions
}
