module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.25.0"

  name               = var.cluster_name
  kubernetes_version = var.kubernetes_version

  endpoint_public_access                   = true
  endpoint_private_access                  = true
  endpoint_public_access_cidrs             = [var.admin_cidr]
  enable_cluster_creator_admin_permissions = true
  authentication_mode                      = "API_AND_CONFIG_MAP"
  cloudwatch_log_group_retention_in_days   = var.cloudwatch_log_retention_days

  upgrade_policy = {
    support_type = "STANDARD"
  }

  addons = {
    vpc-cni = {
      addon_version               = var.addon_versions.vpc_cni
      before_compute              = true
      resolve_conflicts_on_update = "OVERWRITE"
    }
    coredns = {
      addon_version               = var.addon_versions.coredns
      resolve_conflicts_on_update = "OVERWRITE"
    }
    kube-proxy = {
      addon_version               = var.addon_versions.kube_proxy
      before_compute              = true
      resolve_conflicts_on_update = "OVERWRITE"
    }
    eks-pod-identity-agent = {
      addon_version               = var.addon_versions.eks_pod_identity_agent
      resolve_conflicts_on_update = "OVERWRITE"
    }
  }

  vpc_id     = data.aws_vpc.this.id
  subnet_ids = [for s in aws_subnet.eks_public : s.id]

  eks_managed_node_groups = {
    api_a = {
      name           = "${var.cluster_name}-api-a"
      subnet_ids     = [aws_subnet.eks_public["a"].id]
      instance_types = var.api_node_instance_types
      capacity_type  = var.api_node_capacity_type
      min_size       = 1
      desired_size   = 1
      max_size       = 2
      ami_type       = "AL2023_x86_64_STANDARD"
      disk_size      = var.node_disk_size
      labels = {
        workload = "api"
      }
    }
    api_b = {
      name           = "${var.cluster_name}-api-b"
      subnet_ids     = [aws_subnet.eks_public["b"].id]
      instance_types = var.api_node_instance_types
      capacity_type  = var.api_node_capacity_type
      min_size       = 1
      desired_size   = 1
      max_size       = 2
      ami_type       = "AL2023_x86_64_STANDARD"
      disk_size      = var.node_disk_size
      labels = {
        workload = "api"
      }
    }
    ops_a = {
      name           = "${var.cluster_name}-ops-a"
      subnet_ids     = [aws_subnet.eks_public["a"].id]
      instance_types = var.ops_node_instance_types
      capacity_type  = var.ops_node_capacity_type
      min_size       = 1
      desired_size   = 1
      max_size       = 2
      ami_type       = "AL2023_x86_64_STANDARD"
      disk_size      = var.node_disk_size
      labels = {
        workload = "ops"
      }
      taints = {
        dedicated = {
          key    = "dedicated"
          value  = "ops"
          effect = "NO_SCHEDULE"
        }
      }
    }
  }
}
