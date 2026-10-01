package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodContentDraftJpaRepository
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodContentDraftStatus
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class FoodContentDraftSupersedeOrderTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var outboxRepository: FoodContentOutboxJpaRepository
    @Autowired private lateinit var foodIngredientRepository: FoodIngredientJdbcRepository
    @Autowired private lateinit var imageBatchItemRepository: ImageBatchItemJpaRepository
    @Autowired private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository
    @Autowired private lateinit var draftRepository: FoodContentDraftJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun readyFood(name: String): Food = foodRepository.save(
            Food(koreanName = name, displayName = name, description = "공개 중인 설명", spiciness = 1, contentStatus = FoodContentStatus.READY),
        )

        data class Recorded(val calls: List<String>, val pendingRowsWhenInserting: List<Long>)

        fun ingestRecordingDraftCalls(food: Food, description: String): Recorded {
            val calls = mutableListOf<String>()
            val pendingRowsWhenInserting = mutableListOf<Long>()
            val recording = object : FoodContentDraftJpaRepository by draftRepository {
                override fun flush() {
                    calls += "flush"
                    draftRepository.flush()
                }

                override fun <S : FoodContentDraft> save(entity: S): S {
                    calls += "save"
                    pendingRowsWhenInserting += jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM food_content_draft WHERE food_id = ? AND review_status = 'PENDING' AND status = 'ACTIVE'",
                        Long::class.java,
                        entity.foodId,
                    )!!
                    return draftRepository.save(entity)
                }
            }
            val service = AdminFoodContentIngestService(
                foodRepository,
                outboxRepository,
                foodIngredientRepository,
                imageBatchItemRepository,
                vectorOutboxRepository,
                recording,
            )
            val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            TransactionTemplate(transactionManager).executeWithoutResult {
                service.ingestContent(outbox.id, food.id, description, null, 1, emptyMap(), emptyMap(), emptyList())
            }
            return Recorded(calls, pendingRowsWhenInserting)
        }

        fun statusesOf(food: Food): List<FoodContentDraftStatus> = draftRepository.findAll().filter { it.foodId == food.id }.sortedBy { it.id }.map { it.reviewStatus }

        given("공개(READY) 음식의 재수집 결과가 검수 대기 초안을 대체할 때") {
            `when`("이미 검수 대기 초안이 있으면") {
                then("새 초안을 넣는 순간 DB 에 그 음식의 검수 대기 초안이 0개다 — 앞 초안의 대체(UPDATE)가 먼저 나가 있다. INSERT 는 save 즉시 나가므로 대체가 늦으면 그 순간 둘이 된다") {
                    val food = readyFood("대체순서칼국수")
                    ingestRecordingDraftCalls(food, "첫 결과")

                    val recorded = ingestRecordingDraftCalls(food, "두 번째 결과")

                    recorded.pendingRowsWhenInserting shouldBe listOf(0L)
                    recorded.calls shouldBe listOf("flush", "save")
                    statusesOf(food) shouldBe listOf(FoodContentDraftStatus.SUPERSEDED, FoodContentDraftStatus.PENDING)
                }
            }

            `when`("검수 대기 초안이 없으면") {
                then("새 초안만 넣는다 — 불필요한 flush 를 하지 않는다") {
                    val food = readyFood("첫초안칼국수")

                    ingestRecordingDraftCalls(food, "첫 결과") shouldBe Recorded(calls = listOf("save"), pendingRowsWhenInserting = listOf(0L))
                    statusesOf(food) shouldBe listOf(FoodContentDraftStatus.PENDING)
                }
            }
        }
    }
}
