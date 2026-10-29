package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.IngredientDecider
import com.kbap.common.port.llm.IngredientDecisionFood
import com.kbap.common.port.llm.IngredientDecisions
import com.kbap.common.port.llm.IngredientPresence
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class AdminFoodIngredientRetroTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var foods: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var retro: FoodIngredientRetroService

    @Autowired
    private lateinit var foodIngredients: FoodIngredientJdbcRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val mapper = jacksonObjectMapper()

    private class FakeDecider : IngredientDecider {
        val calls = mutableListOf<Pair<String, List<IngredientCode>>>()
        var probabilityOf: (String, IngredientCode) -> Double = { _, _ -> 0.0 }
        var failFor: Set<String> = emptySet()
        var gate: CountDownLatch? = null
        var duringCall: (String) -> Unit = {}

        override fun decide(food: IngredientDecisionFood, codes: List<IngredientCode>): IngredientDecisions {
            synchronized(calls) { calls += food.koreanName to codes }
            gate?.await(10, TimeUnit.SECONDS)
            duringCall(food.koreanName)
            if (food.koreanName in failFor) throw IllegalStateException("boom")
            return IngredientDecisions(codes.associateWith { code -> probabilityOf(food.koreanName, code).let { IngredientPresence(it > 0, it, "근거") } }, 0.5)
        }
    }

    init {
        val fake = FakeDecider()
        val coriander = IngredientCode.valueOf("CORIANDER")
        val fullCodes = IngredientCode.entries.filter { it != coriander }.take(Food.MAX_INGREDIENTS)

        fun food(name: String, status: FoodContentStatus = FoodContentStatus.READY, ingredients: List<FoodIngredient> = listOf(FoodIngredient("SOY", 80))): Food =
            foods.save(
                Food(
                    koreanName = "소급-$name",
                    displayName = "소급-$name",
                    imageRef = "images/webp/food/$name.webp",
                    description = "$name 설명",
                    spiciness = 1,
                    contentStatus = status,
                    ingredients = ingredients,
                ),
            )

        fun post(body: Map<String, Any?>, expected: Int): JsonNode =
            mapper.readTree(
                mockMvc.post("/api/admin/foods/ingredients/retro") {
                    header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                    header("X-API-Version", "1.0")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(body)
                }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            )

        fun job(jobId: String, expected: Int = 200): JsonNode =
            mapper.readTree(
                mockMvc.get("/api/admin/foods/ingredients/retro/$jobId") {
                    header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                    header("X-API-Version", "1.0")
                }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            )

        fun runToEnd(body: Map<String, Any?>): JsonNode {
            val jobId = post(body, 202).path("payload").path("jobId").asText()
            retro.awaitIdle()
            return job(jobId).path("payload")
        }

        fun sql(statement: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(statement) } }

        fun relationPercentOf(food: Food, code: String): Int? = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT fi.inclusion_percent FROM food_ingredient fi JOIN ingredients i ON i.id = fi.ingredient_id WHERE fi.food_id = ${food.id} AND i.code = '$code'")
                    .use { rs -> if (rs.next()) rs.getInt(1) else null }
            }
        }

        fun codesOf(food: Food): Map<String, Int> =
            foods.findById(food.id).orElseThrow().ingredients.orEmpty().associate { it.code to it.inclusionPercent }

        fun clearRedis() = redis.keys("ingredient-retro:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }

        beforeSpec {
            TestTables.clearAll(dataSource)
            retro.decider = fake
        }
        beforeEach {
            TestTables.clearAll(dataSource)
            clearRedis()
            fake.calls.clear()
            fake.probabilityOf = { _, _ -> 0.0 }
            fake.failFor = emptySet()
            fake.gate = null
            fake.duringCall = {}
        }
        afterSpec {
            retro.decider = null
            clearRedis()
            TestTables.clearAll(dataSource)
        }

        given("새 재료 소급 판정 — POST /api/admin/foods/ingredients/retro") {
            `when`("dryRun 으로 부르면") {
                then("호출 0회로 대상 수와 예상 비용만 준다 — READY 이고 아직 그 코드가 없는 음식만 대상이다") {
                    food("고수국수")
                    food("비빔밥")
                    food("이미있음", ingredients = listOf(FoodIngredient("CORIANDER", 70)))
                    food("검수중", status = FoodContentStatus.PENDING_REVIEW)

                    val result = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to true))

                    fake.calls.size shouldBe 0
                    result.path("status").asText() shouldBe "DONE"
                    result.path("dryRun").asBoolean() shouldBe true
                    result.path("targetCount").asInt() shouldBe 2
                    result.path("calledCount").asInt() shouldBe 0
                    result.path("estimatedCostKrw").asDouble() shouldBe (0.6 plusOrMinus 0.0001)
                }
            }

            `when`("실제로 돌리면") {
                then("READY 음식마다 1회 부르고 1% 이상만 round(p×100) 로 더하며, 21개 상한은 건너뛰고 보고한다") {
                    val high = food("고수국수")
                    val low = food("비빔밥")
                    val edge = food("반미")
                    val already = food("이미있음", ingredients = listOf(FoodIngredient("CORIANDER", 70)))
                    val full = food("꽉참", ingredients = fullCodes.map { FoodIngredient(it.name, 10) })
                    val pending = food("검수중", status = FoodContentStatus.PENDING_REVIEW)
                    val versionBefore = foods.findById(high.id).orElseThrow().version
                    fake.probabilityOf = { name, _ -> mapOf("소급-고수국수" to 0.876, "소급-비빔밥" to 0.004, "소급-반미" to 0.01, "소급-꽉참" to 0.5)[name] ?: 0.0 }

                    val result = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))

                    fake.calls.map { it.first } shouldContainExactlyInAnyOrder listOf("소급-고수국수", "소급-비빔밥", "소급-반미", "소급-꽉참")
                    fake.calls.forEach { it.second shouldBe listOf(coriander) }
                    codesOf(high)["CORIANDER"] shouldBe 88
                    codesOf(edge)["CORIANDER"] shouldBe 1
                    codesOf(low).containsKey("CORIANDER") shouldBe false
                    codesOf(already)["CORIANDER"] shouldBe 70
                    codesOf(full).size shouldBe Food.MAX_INGREDIENTS
                    codesOf(pending).containsKey("CORIANDER") shouldBe false
                    val reloaded = foods.findById(high.id).orElseThrow()
                    reloaded.contentStatus shouldBe FoodContentStatus.READY
                    (reloaded.version > versionBefore) shouldBe true
                    dataSource.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery(
                                "SELECT fi.inclusion_percent FROM food_ingredient fi JOIN ingredients i ON i.id = fi.ingredient_id WHERE fi.food_id = ${high.id} AND i.code = 'CORIANDER'",
                            ).use { rs -> rs.next(); rs.getInt(1) shouldBe 88 }
                        }
                    }
                    result.path("status").asText() shouldBe "DONE"
                    result.path("targetCount").asInt() shouldBe 4
                    result.path("calledCount").asInt() shouldBe 4
                    result.path("addedFoodCount").asInt() shouldBe 2
                    result.path("skippedCapFoodIds").map { it.asLong() } shouldBe listOf(full.id)
                    result.path("costKrw").asDouble() shouldBe (2.0 plusOrMinus 0.0001)
                    result.path("probabilityBuckets").path("CORIANDER").let { buckets ->
                        buckets.path("lt1").asInt() shouldBe 1
                        buckets.path("1to10").asInt() shouldBe 1
                        buckets.path("50to90").asInt() shouldBe 2
                    }
                }
            }

            `when`("같은 코드로 두 번째 돌리면") {
                then("1% 미만으로 판정된 음식까지 이미 판정한 것으로 보아 호출 0회다") {
                    food("고수국수")
                    food("비빔밥")
                    fake.probabilityOf = { name, _ -> if (name == "소급-고수국수") 0.9 else 0.0 }
                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    fake.calls.clear()

                    val second = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))

                    fake.calls.size shouldBe 0
                    second.path("targetCount").asInt() shouldBe 0
                }
            }

            `when`("판정이 실패한 음식이 있으면") {
                then("그 음식만 실패 목록에 남기고 판정 기록도 남기지 않아 다음 실행에서 다시 부른다") {
                    val broken = food("실패음식")
                    food("고수국수")
                    fake.probabilityOf = { _, _ -> 0.9 }
                    fake.failFor = setOf("소급-실패음식")

                    val first = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    first.path("failedFoodIds").map { it.asLong() } shouldBe listOf(broken.id)
                    first.path("addedFoodCount").asInt() shouldBe 1

                    fake.calls.clear()
                    fake.failFor = emptySet()
                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    fake.calls.map { it.first } shouldBe listOf("소급-실패음식")
                }
            }

            `when`("재료가 아직 평가되지 않은(null) READY 음식이면") {
                then("부르지 않고 skippedUnassessedFoodIds 로 보고하며 미평가 상태를 그대로 둔다 — 부분 목록이 되면 다른 회피 재료가 UNKNOWN 대신 SAFE 가 된다") {
                    val unassessed = food("미평가")
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food SET ingredients = NULL, ingredients_assessed = 0 WHERE id = ${unassessed.id}") } }
                    fake.probabilityOf = { _, _ -> 0.9 }

                    val result = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))

                    fake.calls.size shouldBe 0
                    result.path("skippedUnassessedFoodIds").map { it.asLong() } shouldBe listOf(unassessed.id)
                    result.path("targetCount").asInt() shouldBe 0
                    val reloaded = foods.findById(unassessed.id).orElseThrow()
                    reloaded.ingredients shouldBe null
                    reloaded.ingredientsAssessed shouldBe false
                }
            }

            `when`("호출은 됐는데 반영 쓰기가 실패하면") {
                then("작업은 끝나되 그 음식은 failedFoodIds 로 남고 호출·비용 합계에도 들어가며, 판정 기록이 없어 다음 실행에서 다시 부른다") {
                    val broken = food("쓰기실패")
                    food("고수국수")
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE food SET ingredients = '[{\"code\": \"NOT_A_CODE\", \"inclusion_percent\": 50}]' WHERE id = ${broken.id}") }
                    }
                    fake.probabilityOf = { _, _ -> 0.9 }

                    val result = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))

                    result.path("status").asText() shouldBe "DONE"
                    result.path("failedFoodIds").map { it.asLong() } shouldBe listOf(broken.id)
                    result.path("calledCount").asInt() shouldBe 2
                    result.path("costKrw").asDouble() shouldBe (1.0 plusOrMinus 0.0001)
                    result.path("addedFoodCount").asInt() shouldBe 1

                    fake.calls.clear()
                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    fake.calls.map { it.first } shouldBe listOf("소급-쓰기실패")
                }
            }

            `when`("상한·미평가·READY 이탈로 반영하지 못한 음식이면") {
                then("판정 기록을 남기지 않아 다음 실행에서 다시 부른다") {
                    val full = food("꽉참", ingredients = fullCodes.map { FoodIngredient(it.name, 10) })
                    val unassessedLater = food("도중미평가")
                    val leftReady = food("도중이탈")
                    fake.probabilityOf = { _, _ -> 0.9 }
                    fake.duringCall = { name ->
                        when (name) {
                            "소급-도중미평가" -> sql("UPDATE food SET ingredients = NULL, ingredients_assessed = 0 WHERE id = ${unassessedLater.id}")
                            "소급-도중이탈" -> sql("UPDATE food SET content_status = 'PENDING_REVIEW' WHERE id = ${leftReady.id}")
                        }
                    }

                    val first = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    first.path("skippedCapFoodIds").map { it.asLong() } shouldBe listOf(full.id)
                    first.path("skippedUnassessedFoodIds").map { it.asLong() } shouldBe listOf(unassessedLater.id)

                    fake.duringCall = {}
                    fake.calls.clear()
                    sql("UPDATE food SET ingredients = '[{\"code\": \"SOY\", \"inclusion_percent\": 80}]', ingredients_assessed = 1 WHERE id = ${unassessedLater.id}")
                    sql("UPDATE food SET content_status = 'READY' WHERE id = ${leftReady.id}")
                    sql("UPDATE food SET ingredients = JSON_REMOVE(ingredients, '$[0]') WHERE id = ${full.id}")

                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))

                    fake.calls.map { it.first } shouldContainExactlyInAnyOrder listOf("소급-꽉참", "소급-도중미평가", "소급-도중이탈")
                }
            }

            `when`("판정 뒤 음식이 바뀌어 version 이 올라가면") {
                then("그 음식만 다시 부르고, 이번 실행이 쓴 음식은 쓴 뒤 version 으로 기록돼 다시 부르지 않는다") {
                    val written = food("반미")
                    val changed = food("비빔밥")
                    val untouched = food("김치찌개")
                    fake.probabilityOf = { name, code -> if (name == "소급-반미" && code == coriander) 0.9 else 0.0 }
                    runToEnd(mapOf("codes" to listOf("CORIANDER", "PORK"), "dryRun" to false))
                    codesOf(written)["CORIANDER"] shouldBe 90
                    fake.calls.clear()

                    sql("UPDATE food SET version = version + 1 WHERE id = ${changed.id}")
                    runToEnd(mapOf("codes" to listOf("CORIANDER", "PORK"), "dryRun" to false))

                    fake.calls.map { it.first } shouldBe listOf("소급-비빔밥")
                    codesOf(untouched).containsKey("CORIANDER") shouldBe false
                }
            }

            `when`("rejudge 로 부르면") {
                then("판정 기록과 이미 있는 코드를 무시하고 전부 다시 불러, 1% 이상은 비율을 바꾸거나 더하고 1% 미만은 JSON·관계에서 뺀다") {
                    val ramen = food("라면", ingredients = listOf(FoodIngredient("SOY", 80), FoodIngredient("CORIANDER", 60)))
                    val banhMi = food("반미", ingredients = listOf(FoodIngredient("CORIANDER", 70)))
                    val pho = food("쌀국수")
                    TransactionTemplate(transactionManager).executeWithoutResult { foodIngredients.replace(ramen.id, ramen.ingredients) }
                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    relationPercentOf(ramen, "CORIANDER") shouldBe 60
                    fake.calls.clear()
                    fake.probabilityOf = { name, _ -> mapOf("소급-라면" to 0.0, "소급-반미" to 0.9, "소급-쌀국수" to 0.5)[name] ?: 0.0 }

                    val dry = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to true, "rejudge" to true))
                    fake.calls.size shouldBe 0
                    dry.path("rejudge").asBoolean() shouldBe true
                    dry.path("targetCount").asInt() shouldBe 3
                    codesOf(ramen)["CORIANDER"] shouldBe 60

                    val result = runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false, "rejudge" to true))

                    fake.calls.map { it.first } shouldContainExactlyInAnyOrder listOf("소급-라면", "소급-반미", "소급-쌀국수")
                    codesOf(ramen) shouldBe mapOf("SOY" to 80)
                    relationPercentOf(ramen, "CORIANDER") shouldBe null
                    relationPercentOf(ramen, "SOY") shouldBe 80
                    codesOf(banhMi)["CORIANDER"] shouldBe 90
                    relationPercentOf(banhMi, "CORIANDER") shouldBe 90
                    codesOf(pho)["CORIANDER"] shouldBe 50
                    result.path("addedCodeCount").asInt() shouldBe 1
                    result.path("removedCodeCount").asInt() shouldBe 1

                    fake.calls.clear()
                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false))
                    fake.calls.size shouldBe 0
                }
            }

            `when`("foodIds 를 주면") {
                then("그 READY 음식만 대상이다") {
                    val target = food("고수국수")
                    food("비빔밥")
                    fake.probabilityOf = { _, _ -> 0.9 }

                    runToEnd(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false, "foodIds" to listOf(target.id)))

                    fake.calls.map { it.first } shouldBe listOf("소급-고수국수")
                }
            }

            `when`("같은 코드 집합이 돌고 있는 중에 또 부르면") {
                then("409 FOOD-023 이고, 끝나면 다시 부를 수 있다") {
                    food("고수국수")
                    val gate = CountDownLatch(1)
                    fake.gate = gate

                    post(mapOf("codes" to listOf("CORIANDER"), "dryRun" to false), 202)
                    post(mapOf("codes" to listOf("CORIANDER"), "dryRun" to true), 409).path("code").asText() shouldBe "FOOD-023"

                    gate.countDown()
                    retro.awaitIdle()
                    post(mapOf("codes" to listOf("CORIANDER"), "dryRun" to true), 202)
                    retro.awaitIdle()
                }
            }

            `when`("모르는 코드나 빈 코드 목록이면") {
                then("400 이다") {
                    post(mapOf("codes" to listOf("NOT_A_CODE"), "dryRun" to true), 400)
                    post(mapOf("codes" to emptyList<String>(), "dryRun" to true), 400)
                }
            }

            `when`("없는 작업을 조회하면") {
                then("404 FOOD-024 다") {
                    job("00000000-0000-0000-0000-000000000000", 404).path("code").asText() shouldBe "FOOD-024"
                }
            }
        }

        given("배경 실행 중 애플리케이션이 내려갈 때") {
            `when`("종료 훅이 돌면") {
                then("돌던 작업은 FAILED 로 끝나고 잠금이 풀려 같은 코드를 바로 다시 시작할 수 있다") {
                    food("고수국수")
                    val gate = CountDownLatch(1)
                    val stopping = FoodIngredientRetroService(
                        StaticListableBeanFactory().getBeanProvider(IngredientDecider::class.java),
                        foods,
                        foodIngredients,
                        redis,
                        transactionManager,
                        0.3,
                    )
                    stopping.decider = FakeDecider().also { it.gate = gate }
                    val job = stopping.startRetro(listOf("CORIANDER"), dryRun = false, rejudge = false, foodIds = null)

                    stopping.stopRunningJobs()
                    gate.countDown()

                    stopping.getRetroJob(job.jobId).let {
                        it.status shouldBe FoodIngredientRetroJob.Status.FAILED
                        (it.finishedAt != null) shouldBe true
                    }
                    redis.hasKey("ingredient-retro:lock:CORIANDER") shouldBe false
                    post(mapOf("codes" to listOf("CORIANDER"), "dryRun" to true), 202)
                    retro.awaitIdle()
                }
            }
        }
    }
}
