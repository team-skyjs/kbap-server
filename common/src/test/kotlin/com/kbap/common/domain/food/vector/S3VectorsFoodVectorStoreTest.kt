package com.kbap.common.domain.food.vector

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.model.GetOutputVector
import java.time.Instant

class S3VectorsFoodVectorStoreTest : BehaviorSpec({
    fun document(imageRef: String? = "foods/7.png") = FoodVectorDocument(
        foodId = 7L,
        name = "김치찌개",
        longDescription = "잘 익은 김치를 돼지고기와 끓인 찌개",
        imageRef = imageRef,
        embedding = FloatArray(256) { it * 0.001f },
        embeddingHash = "sha256:ab",
        embeddingModel = "text-embedding-3-small",
        embeddingDimension = 256,
        indexedAt = Instant.parse("2026-08-31T06:00:00Z"),
    )

    given("S3VectorsFoodVectorStore upsert") {
        `when`("문서를 적재하면") {
            val client = RecordingS3VectorsClient()
            S3VectorsFoodVectorStore(client, "kbap-dev-ecs-vectors", "foods").upsert(document())
            val request = client.puts.single()
            val vector = request.vectors().single()
            val metadata = vector.metadata().asMap()

            then("버킷·인덱스·foodId 키로 PutVectors 를 호출한다") {
                request.vectorBucketName() shouldBe "kbap-dev-ecs-vectors"
                request.indexName() shouldBe "foods"
                vector.key() shouldBe "7"
            }
            then("임베딩은 float32 256 차원 그대로 실린다") {
                vector.data().float32().size shouldBe 256
                vector.data().float32()[3] shouldBe 0.003f
            }
            then("계약 v2 메타데이터 필드가 전부 실린다") {
                metadata.getValue(FoodVectorDocuments.FOOD_ID).asNumber().toLong() shouldBe 7L
                metadata.getValue(FoodVectorDocuments.NAME).asString() shouldBe "김치찌개"
                metadata.getValue(FoodVectorDocuments.LONG_DESCRIPTION).asString() shouldBe "잘 익은 김치를 돼지고기와 끓인 찌개"
                metadata.getValue(FoodVectorDocuments.IMAGE_REF).asString() shouldBe "foods/7.png"
                metadata.getValue(FoodVectorDocuments.EMBEDDING_HASH).asString() shouldBe "sha256:ab"
                metadata.getValue(FoodVectorDocuments.EMBEDDING_MODEL).asString() shouldBe "text-embedding-3-small"
                metadata.getValue(FoodVectorDocuments.EMBEDDING_DIMENSION).asNumber().toInt() shouldBe 256
                metadata.getValue(FoodVectorDocuments.INDEXED_AT).asString() shouldBe "2026-08-31T06:00:00Z"
            }
        }

        `when`("imageRef 가 null 이면") {
            val client = RecordingS3VectorsClient()
            S3VectorsFoodVectorStore(client, "b", "i").upsert(document(imageRef = null))
            val metadata = client.puts.single().vectors().single().metadata().asMap()

            then("S3 Vectors 가 null 값을 거부하므로 키 자체를 생략한다") {
                metadata shouldNotContainKey FoodVectorDocuments.IMAGE_REF
                metadata shouldContainKey FoodVectorDocuments.NAME
            }
        }
    }

    given("S3VectorsFoodVectorStore findEmbeddingHash") {
        `when`("저장된 벡터가 있으면") {
            val client = RecordingS3VectorsClient()
            client.stored = listOf(
                GetOutputVector.builder()
                    .key("7")
                    .metadata(Document.fromMap(mapOf(FoodVectorDocuments.EMBEDDING_HASH to Document.fromString("sha256:ff"))))
                    .build(),
            )
            val hash = S3VectorsFoodVectorStore(client, "b", "i").findEmbeddingHash(7L)

            then("메타데이터의 embeddingHash 를 돌려준다") {
                hash shouldBe "sha256:ff"
            }
            then("foodId 키 하나로 메타데이터만 요청한다") {
                val request = client.gets.single()
                request.keys() shouldContainExactly listOf("7")
                request.returnMetadata() shouldBe true
                request.returnData() shouldBe false
            }
        }

        `when`("저장된 벡터가 없으면") {
            val client = RecordingS3VectorsClient()
            val hash = S3VectorsFoodVectorStore(client, "b", "i").findEmbeddingHash(7L)

            then("null 을 돌려준다") {
                hash.shouldBeNull()
            }
        }
    }

    given("S3VectorsFoodVectorStore delete") {
        `when`("foodId 로 삭제하면") {
            val client = RecordingS3VectorsClient()
            S3VectorsFoodVectorStore(client, "b", "i").delete(7L)

            then("foodId 키로 DeleteVectors 를 호출한다") {
                client.deletes.single().keys() shouldContainExactly listOf("7")
            }
        }
    }
})
