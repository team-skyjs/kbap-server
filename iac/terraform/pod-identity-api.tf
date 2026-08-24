# API workload (kbap-api)용 EKS Pod Identity

locals {
  api_s3_key_prefix = trim(var.api_s3_key_prefix, "/")
}

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "api_pod_identity_assume_role" {
  statement {
    sid = "AllowEksAuthToAssumeRoleForPodIdentity"

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
      values   = [var.api_service_account_namespace]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-service-account"
      values   = [var.api_service_account_name]
    }
  }
}

resource "aws_iam_role" "api_pod_identity" {
  name               = "${var.cluster_name}-api"
  assume_role_policy = data.aws_iam_policy_document.api_pod_identity_assume_role.json

  tags = {
    Name = "${var.cluster_name}-api"
  }
}

data "aws_iam_policy_document" "api_pod_identity_s3" {
  statement {
    sid = "AllowS3ListBucketForScanAssets"

    actions = [
      "s3:ListBucket"
    ]

    resources = [
      "arn:aws:s3:::${var.api_s3_bucket_name}",
    ]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${local.api_s3_key_prefix}/*", ""]
    }
  }

  statement {
    sid = "AllowObjectReadWriteForScanAssets"

    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
      "s3:ListBucketMultipartUploads",
    ]

    resources = [
      "arn:aws:s3:::${var.api_s3_bucket_name}/${local.api_s3_key_prefix}/*",
    ]
  }
}

resource "aws_iam_policy" "api_pod_identity_s3" {
  name   = "${var.cluster_name}-api-s3"
  policy = data.aws_iam_policy_document.api_pod_identity_s3.json
}

resource "aws_iam_role_policy_attachment" "api_pod_identity_s3" {
  role       = aws_iam_role.api_pod_identity.name
  policy_arn = aws_iam_policy.api_pod_identity_s3.arn
}

resource "aws_eks_pod_identity_association" "api" {
  cluster_name    = module.eks_environment.cluster_name
  namespace       = var.api_service_account_namespace
  service_account = var.api_service_account_name
  role_arn        = aws_iam_role.api_pod_identity.arn

  tags = {
    Name      = "${var.cluster_name}-api"
    Workload  = "api"
    Namespace = var.api_service_account_namespace
  }

  depends_on = [module.eks_environment]
}
