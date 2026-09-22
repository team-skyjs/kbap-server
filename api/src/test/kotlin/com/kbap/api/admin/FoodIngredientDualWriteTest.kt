package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.ingredient.IngredientTestSeed
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.InvalidDataAccessApiUsageException
import jakarta.servlet.http.Cookie
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

@IntegrationTest
class FoodIngredientDualWriteTest : AdminFoodCatalogTestSupport() {
    @Autowired
    private lateinit var foodIngredientRepository: FoodIngredientJdbcRepository

    private fun ingredient(code: String, percent: Int) = mapOf("code" to code, "inclusion_percent" to percent)

    private val tooMany = IngredientCode.entries.take(Food.MAX_INGREDIENTS + 1).map { ingredient(it.name, 50) }

    private fun ingest(food: Food, ingredients: List<Map<String, Any>>): ResultActionsDsl {
        val outbox = foodContentOutboxJpaRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
        return mockMvc.post(AdminFoodContentIngestTestSupport.PATH) {
            adminAuth(this)
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(
                AdminFoodContentIngestTestSupport.passedBody(food.id, outbox.id, ingredients = ingredients),
            )
        }
    }

    private fun relationOf(foodId: Long): List<Triple<String, Int, Int>> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                SELECT i.code, fi.inclusion_percent, fi.sort_order
                FROM food_ingredient fi JOIN ingredients i ON i.id = fi.ingredient_id
                WHERE fi.food_id = ? ORDER BY fi.sort_order
                """,
            ).use { ps ->
                ps.setLong(1, foodId)
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getInt(2), rs.getInt(3)) else null }.toList()
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

    init {
        beforeSpec { IngredientTestSeed.restoreCatalog(dataSource) }

        given("콘텐츠 적재") {
            `when`("재료 배열에 0% 항목이 섞여 있으면") {
                then("0% 는 JSON·관계 모두에서 빠지고 남은 항목이 배열 순번×10 순서로 관계에 들어간다") {
                    val food = saveFood("적재관계찌개", FoodContentStatus.FAILED)

                    ingest(food, listOf(ingredient("SOY", 80), ingredient("WHEAT", 0), ingredient("CLAM", 30)))
                        .andExpect { status { isOk() } }

                    relationOf(food.id) shouldBe listOf(Triple("SOY", 80, 10), Triple("CLAM", 30, 20))
                    assessedOf(food.id) shouldBe true
                    foodJpaRepository.findById(food.id).orElseThrow().ingredients?.map { it.code } shouldBe
                        listOf("SOY", "CLAM")
                }
            }

            `when`("같은 code 가 두 번 오면") {
                then("400 FOOD-014 로 거절한다 — 관계 PK 가 code 당 한 행이라 JSON 판정과 어긋날 수 있다") {
                    val food = saveFood("중복재료적재", FoodContentStatus.FAILED)

                    ingest(food, listOf(ingredient("SOY", 10), ingredient("CLAM", 30), ingredient("SOY", 100))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-014") }
                    }

                    relationOf(food.id) shouldBe emptyList()
                }
            }

            `when`("재료가 빈 배열이면") {
                then("관계는 0행이고 조사 완료로 표시된다") {
                    val food = saveFood("빈재료적재", FoodContentStatus.FAILED)

                    ingest(food, emptyList()).andExpect { status { isOk() } }

                    relationOf(food.id) shouldBe emptyList()
                    assessedOf(food.id) shouldBe true
                }
            }

            `when`("재료가 상한을 넘으면") {
                then("400 FOOD-013 로 거절하고 아무것도 바꾸지 않는다") {
                    val food = saveFood("초과재료적재", FoodContentStatus.FAILED)

                    ingest(food, tooMany).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-013") }
                    }

                    relationOf(food.id) shouldBe emptyList()
                    foodJpaRepository.findById(food.id).orElseThrow().description shouldBe "구수한 초과재료적재"
                }
            }
        }

        given("관계 쓰기 저장소") {
            `when`("카탈로그에 없는 code 가 도메인 검증을 우회해 들어오면") {
                then("저장 행 수가 요청과 달라 예외를 던진다 — 조용히 0행이 되지 않는다") {
                    val food = saveFood("우회찌개")

                    shouldThrow<InvalidDataAccessApiUsageException> {
                        foodIngredientRepository.replace(food.id, listOf(FoodIngredient("KIMCHI_PASTE", 50)))
                    }
                }
            }
        }

        given("어드민 음식 수정") {
            `when`("재료를 다른 목록으로 바꾸면") {
                then("관계 집합이 통째로 교체되고 벡터 동기화는 기존처럼 쌓인다") {
                    val food = saveFood("교체찌개")
                    putUpdate(food.id, updateBody("교체찌개", 0) + mapOf("ingredients" to listOf(ingredient("SOY", 80))))
                        .andExpect { status { isOk() } }

                    putUpdate(food.id, updateBody("교체찌개", 1) + mapOf("ingredients" to listOf(ingredient("CLAM", 60))))
                        .andExpect { status { isOk() } }

                    relationOf(food.id) shouldBe listOf(Triple("CLAM", 60, 10))
                    assessedOf(food.id) shouldBe true
                    hasPendingOutbox(food.id, FoodVectorOutboxOperation.UPSERT) shouldBe true
                }
            }

            `when`("재료를 null(미조사)로 되돌리면") {
                then("관계는 0행이고 미조사로 표시된다") {
                    val food = saveFood("미조사찌개")
                    putUpdate(food.id, updateBody("미조사찌개", 0) + mapOf("ingredients" to listOf(ingredient("SOY", 80))))
                        .andExpect { status { isOk() } }

                    putUpdate(food.id, updateBody("미조사찌개", 1) + mapOf("ingredients" to null))
                        .andExpect { status { isOk() } }

                    relationOf(food.id) shouldBe emptyList()
                    assessedOf(food.id) shouldBe false
                }
            }

            `when`("같은 code 가 두 번 오면") {
                then("400 FOOD-014 로 거절한다") {
                    val food = saveFood("중복찌개")

                    putUpdate(
                        food.id,
                        updateBody("중복찌개", 0) + mapOf("ingredients" to listOf(ingredient("SOY", 10), ingredient("SOY", 90))),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-014") }
                    }
                }
            }

            `when`("확률이 0..100 밖이면") {
                then("400 FOOD-015 으로 거절한다 — JSON 에만 남고 관계에서 빠지는 일이 없다") {
                    val food = saveFood("범위밖찌개")

                    putUpdate(food.id, updateBody("범위밖찌개", 0) + mapOf("ingredients" to listOf(ingredient("SOY", 150))))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("FOOD-015") }
                        }

                    relationOf(food.id) shouldBe emptyList()
                    foodJpaRepository.findById(food.id).orElseThrow().ingredients shouldBe emptyList()
                }
            }

            `when`("서버 렌더 편집기가 카탈로그 밖 code 를 보내면") {
                then("도메인 경계가 400 FOOD-016 로 막아 JSON 에도 남지 않는다") {
                    val food = saveFood("미등록찌개")

                    mockMvc.post("/admin/foods/${food.id}") {
                        cookie(Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenOf(MemberRole.ADMIN)))
                        param("koreanName", "미등록찌개")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "READY")
                        param("ingredientsJson", """[{"code":"KIMCHI_PASTE","inclusion_percent":50}]""")
                    }.andExpect { status { isBadRequest() } }

                    relationOf(food.id) shouldBe emptyList()
                    foodJpaRepository.findById(food.id).orElseThrow().ingredients shouldBe emptyList()
                }
            }

            `when`("재료가 상한을 넘으면") {
                then("400 FOOD-013 로 거절한다") {
                    val food = saveFood("초과찌개")

                    putUpdate(food.id, updateBody("초과찌개", 0) + mapOf("ingredients" to tooMany)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-013") }
                    }

                    relationOf(food.id) shouldBe emptyList()
                }
            }
        }
    }
}
