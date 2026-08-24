locals {
  cloudwatch_agent_namespace            = "amazon-cloudwatch"
  cloudwatch_agent_service_account      = "cloudwatch-agent"
  cloudwatch_application_log_group      = "/aws/containerinsights/${var.cluster_name}/application"
  cloudwatch_dataplane_log_group        = "/aws/containerinsights/${var.cluster_name}/dataplane"
  cloudwatch_host_log_group             = "/aws/containerinsights/${var.cluster_name}/host"
  cloudwatch_otel_application_log_group = "/aws/otel/containerinsights/${var.cluster_name}/application"
  cloudwatch_otel_host_log_group        = "/aws/otel/containerinsights/${var.cluster_name}/host"
}

data "aws_iam_policy_document" "cloudwatch_agent_assume_role" {
  statement {
    sid = "AllowEksAuthToAssumeRoleForCloudWatchAgent"

    effect = "Allow"
    actions = [
      "sts:AssumeRole",
      "sts:TagSession",
    ]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-namespace"
      values   = [local.cloudwatch_agent_namespace]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-service-account"
      values   = [local.cloudwatch_agent_service_account]
    }
  }
}

resource "aws_iam_role" "cloudwatch_agent" {
  name               = "${var.cluster_name}-cloudwatch-agent"
  assume_role_policy = data.aws_iam_policy_document.cloudwatch_agent_assume_role.json

  tags = {
    Name = "${var.cluster_name}-cloudwatch-agent"
  }
}

resource "aws_iam_role_policy_attachment" "cloudwatch_agent_server" {
  role       = aws_iam_role.cloudwatch_agent.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_cloudwatch_log_group" "eks_application" {
  name              = local.cloudwatch_application_log_group
  retention_in_days = var.cloudwatch_log_retention_days

  tags = {
    Name = local.cloudwatch_application_log_group
  }
}

resource "aws_cloudwatch_log_group" "eks_dataplane" {
  name              = local.cloudwatch_dataplane_log_group
  retention_in_days = var.cloudwatch_log_retention_days

  tags = {
    Name = local.cloudwatch_dataplane_log_group
  }
}

resource "aws_cloudwatch_log_group" "eks_host" {
  name              = local.cloudwatch_host_log_group
  retention_in_days = var.cloudwatch_log_retention_days

  tags = {
    Name = local.cloudwatch_host_log_group
  }
}

resource "aws_cloudwatch_log_group" "eks_otel_application" {
  name              = local.cloudwatch_otel_application_log_group
  retention_in_days = var.cloudwatch_log_retention_days

  tags = {
    Name = local.cloudwatch_otel_application_log_group
  }
}

resource "aws_cloudwatch_log_group" "eks_otel_host" {
  name              = local.cloudwatch_otel_host_log_group
  retention_in_days = var.cloudwatch_log_retention_days

  tags = {
    Name = local.cloudwatch_otel_host_log_group
  }
}

resource "aws_eks_addon" "cloudwatch_observability" {
  cluster_name  = module.eks_environment.cluster_name
  addon_name    = "amazon-cloudwatch-observability"
  addon_version = var.addon_versions.amazon_cloudwatch_observability

  configuration_values = jsonencode({
    applicationSignals = {
      enabled = false
    }
    containerInsights = {
      enabled = false
    }
    containerLogs = {
      enabled = false
    }
    otelContainerInsights = {
      enabled = true
      logs = {
        enabled = true
      }
    }
  })

  pod_identity_association {
    role_arn        = aws_iam_role.cloudwatch_agent.arn
    service_account = local.cloudwatch_agent_service_account
  }

  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  depends_on = [
    aws_cloudwatch_log_group.eks_application,
    aws_cloudwatch_log_group.eks_dataplane,
    aws_cloudwatch_log_group.eks_host,
    aws_cloudwatch_log_group.eks_otel_application,
    aws_cloudwatch_log_group.eks_otel_host,
    aws_iam_role_policy_attachment.cloudwatch_agent_server,
  ]
}
