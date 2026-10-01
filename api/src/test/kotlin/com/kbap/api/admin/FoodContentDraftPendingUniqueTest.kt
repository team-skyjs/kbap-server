package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@IntegrationTest
class FoodContentDraftPendingUniqueTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun readyFood(name: String): Food = foodRepository.save(
            Food(koreanName = name, displayName = name, description = "공개 중인 설명", spiciness = 1, contentStatus = FoodContentStatus.READY),
        )

        fun draft(food: Food, reviewStatus: String, status: String = "ACTIVE") {
            val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            jdbcTemplate.update(
                "INSERT INTO food_content_draft (food_id, outbox_id, description, spiciness, name_translations, description_translations, " +
                    "review_status, status, created_at, updated_at) VALUES (?, ?, '초안 설명', 1, '{}', '{}', ?, ?, NOW(6), NOW(6))",
                food.id, outbox.id, reviewStatus, status,
            )
        }

        fun rows(food: Food): Long = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM food_content_draft WHERE food_id = ?", Long::class.java, food.id)!!

        given("음식당 검수 대기(PENDING) 초안") {
            `when`("같은 음식에 검수 대기 초안을 하나 더 넣으면") {
                then("DB 가 거절한다 — 코드의 잠금이 놓쳐도 둘이 되지 않는다") {
                    val food = readyFood("유니크칼국수")
                    draft(food, "PENDING")

                    shouldThrow<DuplicateKeyException> { draft(food, "PENDING") }.message shouldContain "uq_food_content_draft_pending_food"

                    rows(food) shouldBe 1L
                }
            }

            `when`("서로 다른 음식이면") {
                then("각자 검수 대기 초안을 하나씩 가진다") {
                    val first = readyFood("유니크비빔밥")
                    val second = readyFood("유니크냉면")

                    draft(first, "PENDING")
                    draft(second, "PENDING")

                    rows(first) shouldBe 1L
                    rows(second) shouldBe 1L
                }
            }

            `when`("대체·승인·반려된 초안이 여러 개 쌓여도") {
                then("막지 않는다 — 이력은 행으로 남고, 그 위에 검수 대기 초안 하나가 더 있을 수 있다") {
                    val food = readyFood("유니크이력찌개")

                    listOf("SUPERSEDED", "SUPERSEDED", "APPROVED", "APPROVED", "REJECTED", "REJECTED").forEach { draft(food, it) }
                    draft(food, "PENDING")

                    rows(food) shouldBe 7L
                }
            }

            `when`("소프트 삭제된 검수 대기 초안이 있으면") {
                then("유니크 자리를 차지하지 않는다 — 코드가 보지 못하는 행 때문에 새 초안이 막히지 않는다") {
                    val food = readyFood("유니크삭제국밥")
                    draft(food, "PENDING", status = "DELETED")

                    draft(food, "PENDING")

                    rows(food) shouldBe 2L
                }
            }
        }

        given("검수 대기 초안 유니크의 스키마") {
            `when`("컬럼과 인덱스를 보면") {
                then("가상 생성 컬럼 위의 유니크 인덱스다 — 엔티티에는 매핑하지 않는다") {
                    jdbcTemplate.queryForObject(
                        "SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food_content_draft' AND COLUMN_NAME = 'pending_food_id'",
                        String::class.java,
                    ) shouldBe "VIRTUAL GENERATED"
                    jdbcTemplate.queryForObject(
                        "SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food_content_draft' AND INDEX_NAME = 'uq_food_content_draft_pending_food'",
                        Int::class.java,
                    ) shouldBe 0
                }
            }
        }
    }
}
