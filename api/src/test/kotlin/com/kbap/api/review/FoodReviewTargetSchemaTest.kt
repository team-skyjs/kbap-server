package com.kbap.api.review

import com.kbap.api.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

@IntegrationTest
class FoodReviewTargetSchemaTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun <T> withRollback(block: (Connection) -> T): T = dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                block(c)
            } finally {
                c.rollback()
            }
        }

        fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }
        fun Connection.single(sql: String): String? = createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } }

        fun Connection.seedTargets(): Pair<Long, Long> {
            exec("INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) VALUES (88401, 'GOOGLE', 'review-target-88401', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))")
            exec(
                "INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                    "VALUES ('스키마음식', '설명', 0, JSON_OBJECT(), JSON_OBJECT(), JSON_ARRAY(), 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
            val foodId = single("SELECT LAST_INSERT_ID()")!!.toLong()
            exec(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, content_hash, collected_at, last_seen_at, created_at, updated_at) " +
                    "VALUES ('RT-1', '스키마칩', '스키마칩', REPEAT('a', 64), NOW(6), NOW(6), NOW(6), NOW(6))",
            )
            val productId = single("SELECT LAST_INSERT_ID()")!!.toLong()
            return foodId to productId
        }

        fun Connection.review(targetType: String?, foodId: Long?, productId: Long?) = exec(
            "INSERT INTO food_review (member_id, food_id, processed_food_id, ${if (targetType != null) "target_type, " else ""}rating, content, status, created_at, updated_at) " +
                "VALUES (88401, ${foodId ?: "NULL"}, ${productId ?: "NULL"}, ${targetType?.let { "'$it', " } ?: ""}4, '리뷰 본문 열 글자 이상', 'ACTIVE', NOW(6), NOW(6))",
        )

        given("가공식품 리뷰 대상 확장 선행 스키마(R-101 exclusive arc)") {
            `when`("마이그레이션 뒤 food_review 를 보면") {
                then("target_type(기본 FOOD)·processed_food_id 와 제품 조회 인덱스·FK 가 있고 food_id 는 NULL 허용이다") {
                    dataSource.connection.use { c ->
                        val columns = c.createStatement().use { s ->
                            s.executeQuery("SELECT column_name, is_nullable, column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'food_review'")
                                .use { r -> buildMap { while (r.next()) put(r.getString(1), r.getString(2) to r.getString(3)) } }
                        }
                        columns.keys shouldContainAll listOf("target_type", "processed_food_id")
                        columns.getValue("target_type") shouldBe ("NO" to "FOOD")
                        columns.getValue("processed_food_id").first shouldBe "YES"
                        columns.getValue("food_id").first shouldBe "YES"
                        c.single(
                            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'food_review' AND index_name = 'idx_food_review_processed_food_recent'",
                        ) shouldBe "2"
                        c.single(
                            "SELECT referenced_table_name FROM information_schema.key_column_usage WHERE table_schema = DATABASE() AND table_name = 'food_review' AND column_name = 'processed_food_id' AND referenced_table_name IS NOT NULL",
                        ) shouldBe "processed_food_product"
                    }
                }
            }
            `when`("기존 방식대로 target_type 없이 음식 리뷰를 넣으면") {
                then("FOOD 로 저장된다 — 구 코드 그대로 호환") {
                    withRollback { c ->
                        val (foodId, _) = c.seedTargets()
                        c.review(null, foodId, null)
                        c.single("SELECT target_type FROM food_review WHERE member_id = 88401") shouldBe "FOOD"
                    }
                }
            }
            `when`("가공식품 리뷰를 넣으면") {
                then("PROCESSED_FOOD + processed_food_id 만 채우면 된다") {
                    withRollback { c ->
                        val (_, productId) = c.seedTargets()
                        c.review("PROCESSED_FOOD", null, productId)
                        c.single("SELECT processed_food_id FROM food_review WHERE member_id = 88401") shouldBe productId.toString()
                    }
                }
            }
            `when`("타입과 대상 컬럼이 어긋나면") {
                then("CHECK 제약이 막는다(3819) — 둘 다·둘 다 없음·타입 반대") {
                    listOf(
                        Triple("FOOD", true, true),
                        Triple("FOOD", false, false),
                        Triple("FOOD", false, true),
                        Triple("PROCESSED_FOOD", true, false),
                        Triple("PROCESSED_FOOD", true, true),
                    ).forEach { (type, withFood, withProduct) ->
                        withRollback { c ->
                            val (foodId, productId) = c.seedTargets()
                            shouldThrow<SQLException> {
                                c.review(type, foodId.takeIf { withFood }, productId.takeIf { withProduct })
                            }.errorCode shouldBe 3819
                        }
                    }
                }
            }
            `when`("없는 가공식품 id 로 넣으면") {
                then("FK 가 막는다(1452)") {
                    withRollback { c ->
                        c.seedTargets()
                        shouldThrow<SQLException> { c.review("PROCESSED_FOOD", null, 999999999L) }.errorCode shouldBe 1452
                    }
                }
            }
        }
    }
}
