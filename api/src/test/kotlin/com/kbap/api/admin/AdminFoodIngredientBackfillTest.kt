package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.ingredient.IngredientTestSeed
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminFoodIngredientBackfillTest : AdminFoodCatalogTestSupport() {
    @Autowired
    private lateinit var foodIngredientRepository: FoodIngredientJdbcRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    private fun seedFoodJson(koreanName: String, ingredientsJson: String?, deleted: Boolean = false): Long {
        val id = saveFood(koreanName).id
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE food SET ingredients = CAST(? AS JSON), status = ?, ingredients_assessed = 0 WHERE id = ?",
            ).use { ps ->
                ps.setString(1, ingredientsJson)
                ps.setString(2, if (deleted) "DELETED" else "ACTIVE")
                ps.setLong(3, id)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun relationOf(foodId: Long): List<Pair<String, Int>> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                SELECT i.code, fi.inclusion_percent FROM food_ingredient fi
                JOIN ingredients i ON i.id = fi.ingredient_id
                WHERE fi.food_id = ? ORDER BY fi.sort_order
                """,
            ).use { ps ->
                ps.setLong(1, foodId)
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) to rs.getInt(2) else null }.toList()
                }
            }
        }

    private fun assessedOf(foodId: Long): Boolean =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT ingredients_assessed FROM food WHERE id = ?").use { ps ->
                ps.setLong(1, foodId)
                ps.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
        }

    private fun backfill(dryRun: Boolean): JsonNode =
        mapper.readTree(
            mockMvc.post("$path/ingredient-backfill?dryRun=$dryRun") { adminAuth(this) }
                .andExpect { status { isOk() } }
                .andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

    private fun report(): JsonNode =
        mapper.readTree(
            mockMvc.get("$path/ingredient-backfill-report") { adminAuth(this) }
                .andExpect { status { isOk() } }
                .andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

    init {
        beforeSpec { IngredientTestSeed.restoreCatalog(dataSource) }

        given("재료 관계 백필") {
            `when`("dryRun 으로 실행하면") {
                then("무엇을 채울지만 세고 아무것도 쓰지 않는다") {
                    val food = seedFoodJson("드라이런찌개", """[{"code":"SOY","inclusion_percent":80}]""")

                    backfill(dryRun = true).path("wouldWrite").asInt() shouldBe 1

                    relationOf(food) shouldBe emptyList()
                    assessedOf(food) shouldBe false
                }
            }

            `when`("실제로 실행하면") {
                then("삭제된 음식까지 관계를 채우고 조사 여부를 갱신한다") {
                    val active = seedFoodJson("백필찌개", """[{"code":"SOY","inclusion_percent":80},{"code":"EGG","inclusion_percent":0}]""")
                    val deleted = seedFoodJson("삭제백필찌개", """[{"code":"CLAM","inclusion_percent":30}]""", deleted = true)
                    val unassessed = seedFoodJson("미조사백필찌개", null)
                    val outboxBefore = vectorOutboxRepository.count()

                    val result = backfill(dryRun = false)
                    result.path("failed").size() shouldBe 0

                    relationOf(active) shouldBe listOf("SOY" to 80)
                    assessedOf(active) shouldBe true
                    relationOf(deleted) shouldBe listOf("CLAM" to 30)
                    assessedOf(deleted) shouldBe true
                    relationOf(unassessed) shouldBe emptyList()
                    assessedOf(unassessed) shouldBe false
                    vectorOutboxRepository.count() shouldBe outboxBefore
                }
            }

            `when`("두 번 실행하면") {
                then("결과가 같다 — 음식 단위 집합 교체라 멱등이다") {
                    val food = seedFoodJson("멱등찌개", """[{"code":"SOY","inclusion_percent":80}]""")

                    backfill(dryRun = false)
                    backfill(dryRun = false)

                    relationOf(food) shouldBe listOf("SOY" to 80)
                }
            }

            `when`("원본이 깨진 음식이 섞여 있으면") {
                then("그 음식만 건너뛰고 목록으로 돌려준다 — 부분 백필을 만들지 않는다") {
                    val broken = seedFoodJson("깨진찌개", """[{"code":"KIMCHI_PASTE","inclusion_percent":50}]""")
                    val sound = seedFoodJson("멀쩡찌개", """[{"code":"SOY","inclusion_percent":80}]""")

                    val result = backfill(dryRun = false)

                    result.path("failed").map { it.path("foodId").asLong() } shouldBe listOf(broken)
                    relationOf(broken) shouldBe emptyList()
                    relationOf(sound) shouldBe listOf("SOY" to 80)
                }
            }
        }

        given("백필 검증 리포트") {
            `when`("백필이 끝난 뒤 조회하면") {
                then("양방향 diff 와 조사 여부 불일치가 모두 0 이다") {
                    seedFoodJson("검증찌개", """[{"code":"SOY","inclusion_percent":80}]""")
                    seedFoodJson("검증미조사찌개", null)
                    backfill(dryRun = false)

                    val report = report()
                    report.path("missingInRelation").size() shouldBe 0
                    report.path("extraInRelation").size() shouldBe 0
                    report.path("assessedMismatch").size() shouldBe 0
                }
            }

            `when`("백필 전이면") {
                then("JSON 에만 있는 음식이 diff 로 잡힌다") {
                    val food = seedFoodJson("미백필찌개", """[{"code":"SOY","inclusion_percent":80}]""")

                    val report = report()
                    report.path("missingInRelation").map { it.asLong() } shouldBe listOf(food)
                    report.path("assessedMismatch").map { it.asLong() } shouldBe listOf(food)
                }
            }
        }

        given("음식 한 건의 관계 조회") {
            `when`("관계 행이 상한을 넘으면") {
                then("sort_order 순으로 21행까지만 준다") {
                    val food = seedFoodJson("상한찌개", "[]")
                    dataSource.connection.use { c ->
                        c.createStatement().use { st ->
                            IngredientTestSeed.allCodes(dataSource).take(22).forEachIndexed { index, ingredientId ->
                                st.execute(
                                    "INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, sort_order) " +
                                        "VALUES ($food, $ingredientId, 50, ${(index + 1) * 10})",
                                )
                            }
                        }
                    }

                    foodIngredientRepository.findByFoodIds(listOf(food)).getValue(food).size shouldBe 21
                }
            }
        }
    }
}
