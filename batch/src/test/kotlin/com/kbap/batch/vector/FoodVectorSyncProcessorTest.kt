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
import javax.sql.DataSource

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

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val embeddingModel = "text-embedding-3-small"
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

        fun saveFood(
            koreanName: String,
            longDescription: String?,
            contentStatus: FoodContentStatus = FoodContentStatus.READY,
        ): Food = foodRepository.save(
            Food(
                koreanName = koreanName,
                displayName = koreanName,
                imageRef = "images/food/$koreanName.webp",
                description = "구수한 $koreanName",
                longDescription = longDescription,
                spiciness = 3,
                ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                contentStatus = contentStatus,
            ),
        )

        fun saveReadyFood(koreanName: String, longDescription: String?): Food = saveFood(koreanName, longDescription)

        fun softDelete(food: Food) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE food SET status = 'DELETED' WHERE id = ?").use {
                    it.setLong(1, food.id)
                    it.executeUpdate()
                }
            }
        }

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
                    val startedAt = Instant.now()

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
                    document.indexedAt.isBefore(startedAt) shouldBe false
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
                        FoodVectorOutbox.upsert(food.id).apply {
                            repeat(FoodVectorOutbox.MAX_ATTEMPTS - 1) { recordFailure("이전 실행 실패") }
                        },
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

        given("외부 호출 실패 처리") {
            val failingEmbedding = TextEmbeddingClient { throw IllegalStateException("임베딩 호출 실패") }
            val failingStore = object : FoodVectorStore {
                override fun findEmbeddingHash(foodId: Long): String? = null

                override fun upsert(document: FoodVectorDocument) = throw IllegalStateException("문서 저장 실패")

                override fun delete(foodId: Long) = Unit
            }

            listOf(
                "임베딩 호출이 실패하면" to { failingEmbedding to InMemoryFoodVectorStore() as FoodVectorStore },
                "문서 저장이 실패하면" to { RecordingEmbeddingClient(embeddingDimension) as TextEmbeddingClient to failingStore },
            ).forEach { (label, collaborators) ->
                `when`(label) {
                    then("시도 횟수와 실패 원인을 기록하고 대기 상태로 남겨 다음 배치에서 재시도한다") {
                        clear()
                        val food = saveReadyFood("들깨칼국수", "들깨가루를 풀어 고소하게 끓인 칼국수")
                        val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                        val (embeddingClient, vectorStore) = collaborators()

                        val summary = processor(embeddingClient, vectorStore).syncAll()

                        val reloaded = outboxRepository.findById(outbox.id).orElseThrow()
                        reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                        reloaded.attempts shouldBe 1
                        reloaded.lastError shouldNotBe null
                        summary shouldBe FoodVectorSyncSummary(attempted = 1, completed = 0, failed = 1)
                    }
                }
            }
        }

        given("제거 대기 건 처리") {
            `when`("적재된 문서가 있으면") {
                then("문서를 제거하고 완료 처리한다") {
                    clear()
                    val food = saveReadyFood("메밀국수", "메밀면을 차가운 육수에 말아 먹는 국수")
                    val outbox = outboxRepository.save(FoodVectorOutbox.delete(food.id))
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()
                    vectorStore.documents[food.id] = document(
                        foodId = food.id,
                        name = "메밀국수",
                        longDescription = "메밀면을 차가운 육수에 말아 먹는 국수",
                        embeddingHash = expectedHash("메밀국수", "메밀면을 차가운 육수에 말아 먹는 국수"),
                        embeddingModel = embeddingModel,
                        embeddingDimension = embeddingDimension,
                    )

                    processor(embeddingClient, vectorStore).syncAll()

                    vectorStore.documents[food.id] shouldBe null
                    embeddingClient.requests shouldBe emptyList()
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                }
            }

            `when`("제거할 문서가 이미 없으면") {
                then("실패가 아니라 완료로 처리한다 — 제거는 멱등이다") {
                    clear()
                    val food = saveReadyFood("비빔냉면", "매콤한 양념에 비벼 먹는 냉면")
                    val outbox = outboxRepository.save(FoodVectorOutbox.delete(food.id))
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()

                    val summary = processor(embeddingClient, vectorStore).syncAll()

                    embeddingClient.requests shouldBe emptyList()
                    vectorStore.documents[food.id] shouldBe null
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                    summary shouldBe FoodVectorSyncSummary(attempted = 1, completed = 1, failed = 0)
                }
            }
        }

        given("적재 대기 건의 처리 시점 자격 재검사") {
            `when`("음식이 조회 가능 상태에서 벗어났으면") {
                then("적재하지 않고 남아 있는 문서를 제거한 뒤 완료 처리한다") {
                    clear()
                    val food = saveFood("수제비", "밀가루 반죽을 떼어 넣고 끓인 국물 요리", FoodContentStatus.PENDING_REVIEW)
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()
                    vectorStore.documents[food.id] = document(
                        foodId = food.id,
                        name = "수제비",
                        longDescription = "예전 설명",
                        embeddingHash = expectedHash("수제비", "예전 설명"),
                        embeddingModel = embeddingModel,
                        embeddingDimension = embeddingDimension,
                    )

                    processor(embeddingClient, vectorStore).syncAll()

                    embeddingClient.requests shouldBe emptyList()
                    vectorStore.documents[food.id] shouldBe null
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                }
            }

            `when`("음식이 삭제됐으면") {
                then("적재하지 않고 남아 있는 문서를 제거한 뒤 완료 처리한다") {
                    clear()
                    val food = saveReadyFood("칼제비", "칼국수와 수제비를 함께 넣고 끓인 요리")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                    softDelete(food)
                    val embeddingClient = RecordingEmbeddingClient(embeddingDimension)
                    val vectorStore = InMemoryFoodVectorStore()
                    vectorStore.documents[food.id] = document(
                        foodId = food.id,
                        name = "칼제비",
                        longDescription = "칼국수와 수제비를 함께 넣고 끓인 요리",
                        embeddingHash = expectedHash("칼제비", "칼국수와 수제비를 함께 넣고 끓인 요리"),
                        embeddingModel = embeddingModel,
                        embeddingDimension = embeddingDimension,
                    )

                    processor(embeddingClient, vectorStore).syncAll()

                    embeddingClient.requests shouldBe emptyList()
                    vectorStore.documents[food.id] shouldBe null
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
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
