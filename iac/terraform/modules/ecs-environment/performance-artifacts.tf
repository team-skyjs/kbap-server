resource "aws_s3_bucket" "performance_artifacts" {
  count = local.performance_profiling_enabled ? 1 : 0

  bucket = "${local.name_prefix}-performance-artifacts"
  tags   = local.common_tags
}

resource "aws_s3_bucket_public_access_block" "performance_artifacts" {
  count = local.performance_profiling_enabled ? 1 : 0

  bucket = aws_s3_bucket.performance_artifacts[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "performance_artifacts" {
  count = local.performance_profiling_enabled ? 1 : 0

  bucket = aws_s3_bucket.performance_artifacts[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "performance_artifacts" {
  count = local.performance_profiling_enabled ? 1 : 0

  bucket = aws_s3_bucket.performance_artifacts[0].id

  rule {
    id     = "expire-performance-artifacts"
    status = "Enabled"

    filter {}

    expiration {
      days = var.performance_artifact_retention_days
    }
  }
}
