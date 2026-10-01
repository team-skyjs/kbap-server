package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.passedBody
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class FoodIngredientReplaceLockingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var foodIngredientRepository: FoodIngredientJdbcRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val mapper = jacksonObjectMapper()

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun scalar(sql: String): String? = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }

        fun food(id: Long, status: String = "FAILED") = exec(
            "INSERT INTO food (id, korean_name, display_name, description, spiciness, name_translations, description_translations, ingredients, " +
                "content_status, status, created_at, updated_at) VALUES ($id, '잠금음식$id', '잠금음식$id', '설명', 0, '{}', '{}', NULL, '$status', 'ACTIVE', NOW(6), NOW(6))",
        )

        fun rowsOf(foodId: Long): Long = scalar("SELECT COUNT(*) FROM food_ingredient WHERE food_id = $foodId")!!.toLong()

        given("재료 관계 행이 없는 두 음식의 재료 교체가 겹칠 때") {
            `when`("한 트랜잭션이 빈 음식을 교체한 채 열려 있고, 다른 트랜잭션이 다른 빈 음식에 재료를 넣은 뒤 앞 트랜잭션도 재료를 넣으면") {
                then("서로 막지 않는다 — 지울 행이 없으면 범위 삭제(갭 잠금)를 하지 않아 교착하지 않는다") {
                    food(68201)
                    food(68202)
                    val firstReplaced = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(2)
                    val first = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            foodIngredientRepository.replace(68201, emptyList())
                            firstReplaced.countDown()
                            Thread.sleep(1_000)
                            foodIngredientRepository.replace(68201, listOf(FoodIngredient("SESAME", 100)))
                        }
                    }
                    firstReplaced.await(30, TimeUnit.SECONDS) shouldBe true
                    val second = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            foodIngredientRepository.replace(68202, listOf(FoodIngredient("WHEAT", 50)))
                        }
                    }
                    executor.shutdown()

                    first.get(30, TimeUnit.SECONDS)
                    second.get(30, TimeUnit.SECONDS)

                    rowsOf(68201) shouldBe 1L
                    rowsOf(68202) shouldBe 1L
                }
            }
        }

        given("재료 없는 새 음식 여러 개의 수집 결과 콜백이 동시에 올 때") {
            `when`("여덟 건이 한꺼번에 들어오면") {
                then("전부 200 이고 음식마다 재료 행이 들어간다 — 교착 희생자가 없다") {
                    val ids = (68211L..68218L).toList()
                    ids.forEach { id ->
                        food(id)
                        exec("INSERT INTO food_content_outbox (food_id, display_name, outbox_status, attempts, status, created_at, updated_at) VALUES ($id, '잠금음식$id', 'PENDING', 0, 'ACTIVE', NOW(6), NOW(6))")
                    }
                    val token = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)
                    val together = CyclicBarrier(ids.size)
                    val executor = Executors.newFixedThreadPool(ids.size)

                    val statuses = executor.invokeAll(
                        ids.map { id ->
                            Callable {
                                val outboxId = scalar("SELECT id FROM food_content_outbox WHERE food_id = $id")!!.toLong()
                                together.await(30, TimeUnit.SECONDS)
                                mockMvc.post(PATH) {
                                    header("Authorization", "Bearer $token")
                                    contentType = MediaType.APPLICATION_JSON
                                    content = mapper.writeValueAsString(
                                        passedBody(
                                            id,
                                            outboxId,
                                            ingredients = listOf(
                                                mapOf("code" to "SESAME", "inclusion_percent" to 60),
                                                mapOf("code" to "WHEAT", "inclusion_percent" to 40),
                                            ),
                                        ),
                                    )
                                }.andReturn().response.status
                            }
                        },
                    ).map { it.get(60, TimeUnit.SECONDS) }
                    executor.shutdown()

                    statuses shouldBe List(ids.size) { 200 }
                    ids.map(::rowsOf) shouldBe List(ids.size) { 2L }
                }
            }
        }

        given("재료 관계 행이 이미 있는 음식의 교체") {
            `when`("다른 재료로 교체하면") {
                then("옛 행은 지워지고 새 행만 남는다") {
                    food(68221)
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        foodIngredientRepository.replace(68221, listOf(FoodIngredient("SESAME", 70), FoodIngredient("WHEAT", 30)))
                    }

                    TransactionTemplate(transactionManager).executeWithoutResult {
                        foodIngredientRepository.replace(68221, listOf(FoodIngredient("WHEAT", 100)))
                    }

                    scalar("SELECT GROUP_CONCAT(i.code) FROM food_ingredient fi JOIN ingredients i ON i.id = fi.ingredient_id WHERE fi.food_id = 68221") shouldBe "WHEAT"
                    scalar("SELECT inclusion_percent FROM food_ingredient WHERE food_id = 68221") shouldBe "100"
                }
            }

            `when`("빈 목록으로 교체하면") {
                then("행이 전부 지워진다") {
                    food(68222)
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        foodIngredientRepository.replace(68222, listOf(FoodIngredient("SESAME", 70)))
                    }

                    TransactionTemplate(transactionManager).executeWithoutResult { foodIngredientRepository.replace(68222, emptyList()) }

                    rowsOf(68222) shouldBe 0L
                }
            }
        }
    }
}
