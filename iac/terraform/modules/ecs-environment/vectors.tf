# 음식 벡터 저장소 — S3 Vectors (KB-328 적재 · KB-319 검색). 서버리스라 상시 비용 0, 인증은 태스크 롤(시크릿 없음).
# 문서 계약: specs/kb-328-food-vector-outbox/contracts/vector-food-document-v2.md — longDescription 은 한글 최대 3KB 라
# filterable 상한(2KB)을 넘을 수 있어 non-filterable 로 선언한다(인덱스 생성 시에만 지정 가능 — 바꾸려면 재생성).
resource "aws_s3vectors_vector_bucket" "foods" {
  vector_bucket_name = "${local.name_prefix}-vectors"
  tags               = local.common_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3vectors_index" "foods" {
  vector_bucket_name = aws_s3vectors_vector_bucket.foods.vector_bucket_name
  index_name         = "foods"
  data_type          = "float32"
  dimension          = 256
  distance_metric    = "cosine"
  tags               = local.common_tags

  metadata_configuration {
    non_filterable_metadata_keys = ["longDescription"]
  }

  lifecycle {
    prevent_destroy = true
  }
}

locals {
  # 앱 스위치 — 배치의 foodVectorSyncJob 은 kbap.vector.enabled ∧ kbap.llm.embedding.enabled 일 때만 조립되므로 둘을 함께 켠다.
  vector_env = var.vector_enabled ? {
    VECTOR_ENABLED    = "true"
    EMBEDDING_ENABLED = "true"
    VECTOR_BUCKET     = aws_s3vectors_vector_bucket.foods.vector_bucket_name
    VECTOR_INDEX      = aws_s3vectors_index.foods.index_name
  } : {}
}
