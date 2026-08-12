package com.kbap.batch.vector

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.vector.FoodVectorDocument
import com.kbap.common.domain.food.vector.FoodVectorStore
import com.kbap.common.port.llm.TextEmbeddingClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import java.security.MessageDigest
import java.time.Instant

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodVectorSyncProcessorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val embeddingModel = "amazon.titan-embed-text-v2:0"
        val embeddingDimension = 256

        fun expectedHash(name: String, longDescription: String): String {
            val raw = "$embeddingModel|$embeddingDimension|$name\n$longDescription"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return "sha256:" + digest.joinToString("") { "%02x".format(it) }
        }

        fun clear() {
            outboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        fun saveReadyFood(koreanName: String, longDescription: String?): Food = foodRepository.save(
            Food(
                koreanName = koreanName,
                displayName = koreanName,
                imageRef = "images/food/$koreanName.webp",
                description = "구수한 $koreanName",
                longDescription = longDescription,
                spiciness = 3,
                ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                contentStatus = FoodContentStatus.READY,
            ),
        )

        fun processor(
            embeddingClient: TextEmbeddingClient,
            vectorStore: FoodVectorStore,
        ) = FoodVectorSyncProcessor(
            outboxRepository,
            foodRepository,
            embeddingClient,
            vectorStore,
            transactionManager,
            embeddingModel,
            embeddingDimension,
            pageSize = 2,
        )

        given("적재 대기 건 처리") {
            `when`("벡터 문서가 아직 없으면") {
                then("긴 설명을 임베딩해 문서를 적재하고 완료 처리한다") {
                    clear()
                    val food = saveReadyFood("김치찌개", "잘 익은 김치와 돼지고기를 넣고 끓인 한국의 대표적인 찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()

                    val summary = processor(embeddingClient, vectorStore).syncAll()

                    embeddingClient.requests shouldBe listOf(
                        listOf("김치찌개\n잘 익은 김치와 돼지고기를 넣고 끓인 한국의 대표적인 찌개"),
                    )
                    val document = vectorStore.documents.getValue(food.id)
                    document.name shouldBe "김치찌개"
                    document.longDescription shouldBe "잘 익은 김치와 돼지고기를 넣고 끓인 한국의 대표적인 찌개"
                    document.imageRef shouldBe "images/food/김치찌개.webp"
                    document.embedding.size shouldBe embeddingDimension
                    document.embeddingModel shouldBe embeddingModel
                    document.embeddingDimension shouldBe embeddingDimension
                    document.embeddingHash shouldBe
                        expectedHash("김치찌개", "잘 익은 김치와 돼지고기를 넣고 끓인 한국의 대표적인 찌개")
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                    summary shouldBe FoodVectorSyncSummary(attempted = 1, completed = 1, failed = 0)
                }
            }

            `when`("적재된 문서의 임베딩 해시가 같으면") {
                then("임베딩을 다시 호출하지 않고 완료 처리한다") {
                    clear()
                    val food = saveReadyFood("된장찌개", "구수한 된장과 두부를 넣고 끓인 찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()
                    vectorStore.documents[food.id] = document(
                        foodId = food.id,
                        name = "된장찌개",
                        longDescription = "구수한 된장과 두부를 넣고 끓인 찌개",
                        embeddingHash = expectedHash("된장찌개", "구수한 된장과 두부를 넣고 끓인 찌개"),
                        embeddingModel = embeddingModel,
                        embeddingDimension = embeddingDimension,
                    )

                    processor(embeddingClient, vectorStore).syncAll()

                    embeddingClient.requests shouldBe emptyList()
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                }
            }

            `when`("적재된 문서의 임베딩 해시가 다르면") {
                then("다시 임베딩해 문서를 교체한다") {
                    clear()
                    val food = saveReadyFood("순두부찌개", "부드러운 순두부와 해물을 넣고 끓인 얼큰한 찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()
                    vectorStore.documents[food.id] = document(
                        foodId = food.id,
                        name = "순두부찌개",
                        longDescription = "예전 설명",
                        embeddingHash = expectedHash("순두부찌개", "예전 설명"),
                        embeddingModel = embeddingModel,
                        embeddingDimension = embeddingDimension,
                    )

                    processor(embeddingClient, vectorStore).syncAll()

                    embeddingClient.requests.size shouldBe 1
                    val document = vectorStore.documents.getValue(food.id)
                    document.longDescription shouldBe "부드러운 순두부와 해물을 넣고 끓인 얼큰한 찌개"
                    document.embeddingHash shouldBe
                        expectedHash("순두부찌개", "부드러운 순두부와 해물을 넣고 끓인 얼큰한 찌개")
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                }
            }
        }

        given("적재할 수 없는 음식 처리") {
            listOf("긴 설명이 없으면" to null, "긴 설명이 공백뿐이면" to "   ").forEach { (label, longDescription) ->
                `when`(label) {
                    then("임베딩 없이 실패로 기록하고 대기 상태를 유지한다") {
                        clear()
                        val food = saveReadyFood("칼국수", longDescription)
                        val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                        val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                        val vectorStore = InMemoryFoodVectorStore()

                        val summary = processor(embeddingClient, vectorStore).syncAll()

                        embeddingClient.requests shouldBe emptyList()
                        vectorStore.documents[food.id] shouldBe null
                        val reloaded = outboxRepository.findById(outbox.id).orElseThrow()
                        reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                        reloaded.attempts shouldBe 1
                        reloaded.lastError shouldNotBe null
                        summary shouldBe FoodVectorSyncSummary(attempted = 1, completed = 0, failed = 1)
                    }
                }
            }

            `when`("실패가 최대 시도 횟수에 도달하면") {
                then("실패 상태로 격리해 다음 배치가 다시 집지 않는다") {
                    clear()
                    val food = saveReadyFood("잔치국수", null)
                    val outbox = outboxRepository.save(
                        FoodVectorOutbox.upsert(food.id).apply { attempts = FoodVectorOutbox.MAX_ATTEMPTS - 1 },
                    )
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()

                    processor(embeddingClient, vectorStore).syncAll()

                    val reloaded = outboxRepository.findById(outbox.id).orElseThrow()
                    reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.FAILED
                    reloaded.attempts shouldBe FoodVectorOutbox.MAX_ATTEMPTS
                    outboxRepository.findPendingAfterId(0, 10) shouldBe emptyList()
                }
            }
        }
    }
}

private fun document(
    foodId: Long,
    name: String,
    longDescription: String,
    embeddingHash: String,
    embeddingModel: String,
    embeddingDimension: Int,
) = FoodVectorDocument(
    foodId = foodId,
    name = name,
    longDescription = longDescription,
    imageRef = null,
    embedding = FloatArray(embeddingDimension) { 0.5f },
    embeddingHash = embeddingHash,
    embeddingModel = embeddingModel,
    embeddingDimension = embeddingDimension,
    indexedAt = Instant.parse("2026-08-01T00:00:00Z"),
)

private class RecordingEmbeddingClient(private val dimension: Int) : TextEmbeddingClient {
    val requests = mutableListOf<List<String>>()

    override fun embed(texts: List<String>): List<FloatArray> {
        requests += texts
        return texts.map { FloatArray(dimension) { index -> index * 0.001f } }
    }
}

private class InMemoryFoodVectorStore : FoodVectorStore {
    val documents = mutableMapOf<Long, FoodVectorDocument>()

    override fun findEmbeddingHash(foodId: Long): String? = documents[foodId]?.embeddingHash

    override fun upsert(document: FoodVectorDocument) {
        documents[document.foodId] = document
    }

    override fun delete(foodId: Long) {
        documents.remove(foodId)
    }
}
