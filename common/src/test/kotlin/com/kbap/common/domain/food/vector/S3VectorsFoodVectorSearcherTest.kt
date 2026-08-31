package com.kbap.common.domain.food.vector

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.services.s3vectors.model.QueryOutputVector

class S3VectorsFoodVectorSearcherTest : BehaviorSpec({
    given("S3VectorsFoodVectorSearcher") {
        `when`("질의 벡터로 검색하면") {
            val client = RecordingS3VectorsClient()
            client.matches = listOf(
                QueryOutputVector.builder().key("7").distance(0.25f).build(),
                QueryOutputVector.builder().key("not-a-food").distance(0.5f).build(),
                QueryOutputVector.builder().key("9").distance(0.9f).build(),
            )
            val results = S3VectorsFoodVectorSearcher(client, "b", "foods").search(FloatArray(256) { 0.01f }, 3)

            then("topK·거리 반환을 켜고 QueryVectors 를 호출한다") {
                val request = client.queries.single()
                request.indexName() shouldBe "foods"
                request.topK() shouldBe 3
                request.returnDistance() shouldBe true
                request.queryVector().float32().size shouldBe 256
            }
            then("코사인 거리를 유사도 점수(1 - distance)로 바꿔 순서대로 돌려주고, 숫자가 아닌 키는 버린다") {
                results.map { it.foodId } shouldBe listOf(7L, 9L)
                results[0].score shouldBe (0.75 plusOrMinus 1e-6)
                results[1].score shouldBe (0.1 plusOrMinus 1e-6)
            }
        }
    }
})
