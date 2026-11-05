package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class AdminMenuNameEstimatePromoteTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var foodRepository: FoodJpaRepository

    private val mapper = jacksonObjectMapper()

    init {
        val adminId = 7L
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun adminToken() = tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)

        fun seedEstimate(nameKey: String, kind: String = "DISH"): Long {
            execute(
                "INSERT INTO menu_name_estimate (name_key, kind, english_name, description, avoidance_codes, spiciness, confidence, model, prompt_version, status, created_at, updated_at) " +
                    "VALUES ('$nameKey', '$kind', 'Eng $nameKey', 'About $nameKey', '[\"BEEF\"]', 1, 0.9, 'gpt-5-nano', 'v1', 'ACTIVE', NOW(6), NOW(6))",
            )
            return longOf("SELECT id FROM menu_name_estimate WHERE name_key = '$nameKey'")
        }
        fun seedReadyFood(nameKey: String): Food = foodRepository.save(
            Food(
                koreanName = nameKey,
                displayName = nameKey,
                imageRef = "images/food/$nameKey.webp",
                description = "구수한 $nameKey",
                longDescription = "$nameKey 는 한국 음식이다",
                spiciness = 1,
                ingredients = listOf(FoodIngredient("BEEF", 100)),
                contentStatus = FoodContentStatus.READY,
            ),
        )
        fun seedScans(foodId: Long, count: Int) = repeat(count) {
            execute("INSERT INTO scan_history (member_id, price, food_id, status, created_at, updated_at) VALUES (9917, NULL, $foodId, 'ACTIVE', NOW(6), NOW(6))")
        }
        fun pendingOutboxes(foodId: Long) = longOf("SELECT COUNT(*) FROM food_content_outbox WHERE food_id = $foodId AND outbox_status = 'PENDING' AND status = 'ACTIVE'")

        fun list(query: String = ""): JsonNode = mapper.readTree(
            mockMvc.get("/api/admin/menu-name-estimates$query") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer ${adminToken()}")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun promote(id: Long, target: String? = "dev"): Pair<Int, JsonNode> = mockMvc.post("/api/admin/menu-name-estimates/$id/promote") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${adminToken()}")
            target?.let { header("X-Target-Environment", it) }
        }.andReturn().response.let { it.status to mapper.readTree(it.getContentAsString(Charsets.UTF_8).ifBlank { "{}" }) }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM menu_name_estimate")
            execute(
                "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, avoidance_substance_codes, status, created_at, updated_at) " +
                    "VALUES (9917, 'GOOGLE', 'promote-9917', 'ACTIVE', 1, '[]', 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM menu_name_estimate")
        }

        given("어드민 AI 추정 메뉴 목록 — S-966") {
            `when`("추정 행마다 같은 키 음식 상태와 스캔 횟수를 보면") {
                then("스캔 많은 순으로, 음식 상태·삭제된 음식·승격 가능 여부를 함께 준다") {
                    val failedKey = "추정대기국"
                    val readyKey = "추정완성국"
                    val deletedKey = "추정삭제국"
                    seedEstimate(failedKey)
                    seedEstimate(readyKey, kind = "DRINK")
                    seedEstimate(deletedKey)
                    val failed = foodRepository.save(Food.failed(failedKey))
                    val ready = seedReadyFood(readyKey)
                    val deleted = foodRepository.save(Food.failed("${deletedKey}_deleted_1"))
                    execute("UPDATE food SET status = 'DELETED', deleted_original_korean_name = '$deletedKey' WHERE id = ${deleted.id}")
                    seedScans(failed.id, 3)
                    seedScans(ready.id, 1)

                    val page = list()

                    page.path("totalElements").asLong() shouldBe 3
                    page.path("items").map { it.path("nameKey").asText() } shouldBe listOf(failedKey, readyKey, deletedKey)
                    page.path("items")[0].let {
                        it.path("scanCount").asLong() shouldBe 3
                        it.path("kind").asText() shouldBe "DISH"
                        it.path("englishName").asText() shouldBe "Eng $failedKey"
                        it.path("avoidanceCodes").map { c -> c.asText() } shouldBe listOf("BEEF")
                        it.path("food").path("id").asLong() shouldBe failed.id
                        it.path("food").path("contentStatus").asText() shouldBe "FAILED"
                        it.path("food").path("hasImage").asBoolean() shouldBe false
                        it.path("promotable").asBoolean() shouldBe true
                        it.path("promotedAt").isNull shouldBe true
                    }
                    page.path("items")[1].let {
                        it.path("food").path("contentStatus").asText() shouldBe "READY"
                        it.path("food").path("hasImage").asBoolean() shouldBe true
                        it.path("promotable").asBoolean() shouldBe false
                    }
                    page.path("items")[2].let {
                        it.path("scanCount").asLong() shouldBe 0
                        it.path("food").isNull shouldBe true
                        it.path("deletedFoodId").asLong() shouldBe deleted.id
                        it.path("promotable").asBoolean() shouldBe true
                    }
                    list("?foodStatus=NONE").path("items").map { it.path("nameKey").asText() } shouldBe listOf(deletedKey)
                    list("?kind=DRINK").path("items").map { it.path("nameKey").asText() } shouldBe listOf(readyKey)
                    list("?sort=RECENT&size=1").let {
                        it.path("items").map { i -> i.path("nameKey").asText() } shouldBe listOf(deletedKey)
                        it.path("hasNext").asBoolean() shouldBe true
                    }
                }
            }
        }

        given("어드민 AI 추정 메뉴를 음식으로 등록(승격) — S-966") {
            `when`("같은 키 음식이 없으면") {
                then("미완성 음식을 만들고 콘텐츠를 요청하며 승격 시각·승격자를 남긴다 — 목록에서는 빠지지 않고 상태만 바뀐다") {
                    val id = seedEstimate("추정새음식")

                    val (status, body) = promote(id)

                    status shouldBe 200
                    body.path("payload").path("outcome").asText() shouldBe "CREATED_FOOD"
                    val foodId = body.path("payload").path("foodId").asLong()
                    longOf("SELECT COUNT(*) FROM food WHERE id = $foodId AND korean_name = '추정새음식' AND content_status = 'FAILED'") shouldBe 1
                    pendingOutboxes(foodId) shouldBe 1
                    longOf("SELECT promoted_by FROM menu_name_estimate WHERE id = $id") shouldBe adminId
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE id = $id AND promoted_at IS NOT NULL") shouldBe 1
                    list("?promoted=true").path("items").single().let {
                        it.path("id").asLong() shouldBe id
                        it.path("promotable").asBoolean() shouldBe false
                        it.path("promotedBy").asLong() shouldBe adminId
                    }
                    list("?promoted=false").path("items").size() shouldBe 0
                }
            }

            `when`("준비 전 음식이 있으면") {
                then("그 음식에 콘텐츠를 다시 요청한다") {
                    val id = seedEstimate("추정재요청국")
                    val food = foodRepository.save(Food.failed("추정재요청국"))

                    val (status, body) = promote(id)

                    status shouldBe 200
                    body.path("payload").path("outcome").asText() shouldBe "CONTENT_REQUESTED"
                    body.path("payload").path("foodId").asLong() shouldBe food.id
                    pendingOutboxes(food.id) shouldBe 1
                }
            }

            `when`("이미 대기 중인 콘텐츠 요청이 있으면") {
                then("요청을 겹쳐 쌓지 않고 승격만 표시한다") {
                    val id = seedEstimate("추정대기중국")
                    val food = foodRepository.save(Food.failed("추정대기중국"))
                    execute("INSERT INTO food_content_outbox (food_id, display_name, outbox_status, attempts, status, created_at, updated_at) VALUES (${food.id}, '추정대기중국', 'PENDING', 0, 'ACTIVE', NOW(6), NOW(6))")

                    val (status, body) = promote(id)

                    status shouldBe 200
                    body.path("payload").path("outcome").asText() shouldBe "ALREADY_PENDING"
                    pendingOutboxes(food.id) shouldBe 1
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE id = $id AND promoted_at IS NOT NULL") shouldBe 1
                }
            }

            `when`("같은 키 음식이 이미 READY 이면") {
                then("409 FOOD-027 이고 승격하지 않는다") {
                    val id = seedEstimate("추정이미완성")
                    val food = seedReadyFood("추정이미완성")

                    val (status, body) = promote(id)

                    status shouldBe 409
                    body.path("code").asText() shouldBe "FOOD-027"
                    pendingOutboxes(food.id) shouldBe 0
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE id = $id AND promoted_at IS NULL") shouldBe 1
                }
            }

            `when`("이미 승격한 추정을 다시 승격하면") {
                then("409 SCAN-016 이다") {
                    val id = seedEstimate("추정두번국")
                    promote(id).first shouldBe 200

                    val (status, body) = promote(id)

                    status shouldBe 409
                    body.path("code").asText() shouldBe "SCAN-016"
                }
            }

            `when`("없는 추정 id 면") {
                then("404 SCAN-015 이다") {
                    val (status, body) = promote(987_654)

                    status shouldBe 404
                    body.path("code").asText() shouldBe "SCAN-015"
                }
            }

            `when`("X-Target-Environment 가 서버 환경과 다르거나 없으면") {
                then("다르면 409 FOOD-026, 없으면 400 이고 아무것도 바꾸지 않는다") {
                    val id = seedEstimate("추정환경국")

                    promote(id, target = "prod").let { (status, body) ->
                        status shouldBe 409
                        body.path("code").asText() shouldBe "FOOD-026"
                    }
                    promote(id, target = null).first shouldBe 400
                    longOf("SELECT COUNT(*) FROM food WHERE korean_name = '추정환경국'") shouldBe 0
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE id = $id AND promoted_at IS NULL") shouldBe 1
                }
            }

            `when`("같은 추정을 두 어드민이 동시에 승격하면") {
                then("하나만 200 이고 다른 하나는 409 SCAN-016 — 음식·콘텐츠 요청은 한 건씩이다") {
                    val id = seedEstimate("추정동시국")
                    val start = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    val results = (1..2).map { pool.submit<Pair<Int, JsonNode>> { start.await(); promote(id) } }
                    start.countDown()
                    val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
                    pool.shutdown()

                    outcomes.map { it.first }.sorted() shouldBe listOf(200, 409)
                    outcomes.single { it.first == 409 }.second.path("code").asText() shouldBe "SCAN-016"
                    longOf("SELECT COUNT(*) FROM food WHERE korean_name = '추정동시국'") shouldBe 1
                    pendingOutboxes(longOf("SELECT id FROM food WHERE korean_name = '추정동시국'")) shouldBe 1
                }
            }
        }
    }
}
