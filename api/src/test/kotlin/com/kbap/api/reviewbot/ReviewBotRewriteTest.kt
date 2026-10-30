package com.kbap.api.reviewbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ReviewBotRewriteTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var accountService: ReviewBotAccountService
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var fakeGenerator: FakeReviewTextGenerator
    @Autowired private lateinit var rewriter: ReviewBotRewriteService

    private val mapper = jacksonObjectMapper()

    init {
        val long = "So good. ".repeat(12).trim()
        val short = "Crispy and so good lol"

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun rows(sql: String): List<List<Any?>> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) (1..rs.metaData.columnCount).map { rs.getObject(it) } else null }.toList() }
            }
        }
        fun review(botId: Long, foodId: Long, content: String, language: String?, rating: Int = 4): Long {
            execute(
                "INSERT INTO food_review (member_id, target_type, food_id, rating, serving_speed_rating, staff_kindness_rating, content, language, image_refs, status, created_at, updated_at, version) " +
                    "VALUES ($botId, 'FOOD', $foodId, $rating, 0, 0, '$content', ${language?.let { "'$it'" } ?: "NULL"}, '[\"bot/1.webp\"]', 'ACTIVE', '2026-10-01 10:00:00', '2026-10-01 10:00:00', 0)",
            )
            return (rows("SELECT MAX(id) FROM food_review").single().single() as Number).toLong()
        }
        fun contentOf(id: Long) = rows("SELECT content FROM food_review WHERE id = $id").single().single() as String

        lateinit var bots: List<Long>
        var food = 0L

        beforeEach {
            TestTables.clearAll(dataSource)
            fakeGenerator.reset()
            bots = accountService.ensureBots(4).bots.map { it.id }
            food = foodRepository.save(Food(koreanName = "재작성음식", description = "설명", contentStatus = FoodContentStatus.READY)).id
            fakeGenerator.reply = { short }
        }

        given("기존 봇 리뷰 말투 재작성(KB-943)") {
            `when`("dryRun 이면") {
                then("상한 안 리뷰는 대상에서 빼고, 뽑힌 리뷰를 같은 언어·별점으로 생성까지 하되 저장하지 않는다") {
                    val en = review(bots[0], food, long, "en", rating = 5)
                    val ja = review(bots[1], food, long, "ja", rating = 3)
                    review(bots[2], food, short, "en")

                    val result = rewriter.rewrite("dev", ratio = 1.0, dryRun = true, limit = 40, seed = 1)

                    result.candidates shouldBe 2
                    result.selected shouldBe 2
                    result.rewritten shouldBe 2
                    result.alreadyShort shouldBe 1
                    result.sample.map { it.reviewId } shouldContainExactlyInAnyOrder listOf(en, ja)
                    result.sample.all { it.before == long && it.after == short } shouldBe true
                    contentOf(en) shouldBe long
                    fakeGenerator.requests.map { it.language to it.rating } shouldContainExactlyInAnyOrder listOf("English" to 5, "Japanese" to 3)
                }
            }

            `when`("같은 seed 로 여러 번 나눠 실행하면") {
                then("리뷰별로 고정된 표본에만 수렴해 비율을 넘지 않고, 다시 쓴 리뷰는 content·updated_at 만 바뀐다") {
                    val foods = (1..8).map { foodRepository.save(Food(koreanName = "재작성음식$it", description = "설명", contentStatus = FoodContentStatus.READY)).id }
                    val ids = (0 until 8).map { review(bots[it % 4], foods[it], long, "en") }
                    val planned = rewriter.rewrite("dev", ratio = 0.5, dryRun = true, limit = 40, seed = 7).candidates
                    (planned in 1..7) shouldBe true

                    val rewritten = (1..planned + 3).flatMap {
                        rewriter.rewrite("dev", ratio = 0.5, dryRun = false, limit = 1, seed = 7).sample.map { it.reviewId }
                    }

                    rewritten.size shouldBe planned
                    rewritten.toSet().size shouldBe planned
                    ids.forEach { id -> contentOf(id) shouldBe if (id in rewritten) short else long }
                    rewriter.rewrite("dev", ratio = 0.5, dryRun = true, limit = 40, seed = 7).candidates shouldBe 0
                    rewritten.forEach { id ->
                        rows("SELECT rating, CAST(image_refs AS CHAR), created_at, updated_at FROM food_review WHERE id = $id").single().let { (rating, images, created, updated) ->
                            (rating as Number).toInt() shouldBe 4
                            images.toString().contains("bot/1.webp") shouldBe true
                            created.toString().startsWith("2026-10-01T10:00") shouldBe true
                            (updated.toString() != created.toString()) shouldBe true
                        }
                    }
                }
            }

            `when`("같은 seed 로 dryRun 과 실행을 부르면") {
                then("리뷰마다 같은 스타일·문장 수로 요청한다 — 예진에게 보인 dryRun 결과와 실행 결과의 결이 같다") {
                    val foods = (1..3).map { foodRepository.save(Food(koreanName = "스타일음식$it", description = "설명", contentStatus = FoodContentStatus.READY)).id }
                    (0 until 3).forEach { review(bots[it], foods[it], long, "en") }
                    rewriter.rewrite("dev", ratio = 1.0, dryRun = true, limit = 40, seed = 11)
                    val planned = fakeGenerator.requests.map { it.style to it.sentences }
                    fakeGenerator.reset()
                    fakeGenerator.reply = { short }

                    rewriter.rewrite("dev", ratio = 1.0, dryRun = false, limit = 40, seed = 11)

                    fakeGenerator.requests.map { it.style to it.sentences } shouldBe planned
                }
            }

            `when`("첫 시도의 생성물이 금지어 가드에 걸리면") {
                then("재시도는 스타일을 다시 뽑아 다른 스타일로 요청하고 그 결과로 저장한다") {
                    val id = review(bots[0], food, long, "en")
                    fakeGenerator.reply = { if (fakeGenerator.requests.size == 1) "Loved it, the restaurant staff were great." else short }

                    val result = rewriter.rewrite("dev", ratio = 1.0, dryRun = false, limit = 40, seed = 11)

                    result.rewritten shouldBe 1
                    contentOf(id) shouldBe short
                    fakeGenerator.requests.size shouldBe 2
                    (fakeGenerator.requests[0].style != fakeGenerator.requests[1].style) shouldBe true
                }
            }

            `when`("앞쪽 리뷰가 매번 실패하고 limit 가 작으면") {
                then("nextAfterId 로 그 리뷰를 지나 다음 리뷰를 처리하고, 끝이면 nextAfterId 가 null 이다") {
                    val stuckFood = foodRepository.save(Food(koreanName = "영구실패음식", description = "설명", contentStatus = FoodContentStatus.READY)).id
                    val stuck = review(bots[0], stuckFood, long, "en")
                    val next = review(bots[1], food, long, "en")
                    fakeGenerator.reply = { if (it.foodName.contains("영구실패")) "Loved it, the restaurant staff were great." else short }

                    val first = rewriter.rewrite("dev", ratio = 1.0, dryRun = false, limit = 1, seed = 5)
                    first.failedIds shouldBe listOf(stuck)
                    first.nextAfterId shouldBe stuck

                    val second = rewriter.rewrite("dev", ratio = 1.0, dryRun = false, limit = 1, seed = 5, afterId = first.nextAfterId)
                    second.rewritten shouldBe 1
                    contentOf(next) shouldBe short
                    second.nextAfterId shouldBe null
                    second.candidates shouldBe 1
                }
            }

            `when`("본문 언어가 비어 있으면") {
                then("봇 국가 언어로, 그것도 없으면 영어로 다시 쓴다") {
                    execute("UPDATE member SET country_code = 'JP' WHERE id = ${bots[0]}")
                    execute("UPDATE member SET country_code = 'US' WHERE id = ${bots[1]}")
                    review(bots[0], food, long, null)
                    review(bots[1], food, long, null)

                    rewriter.rewrite("dev", ratio = 1.0, dryRun = true, limit = 40, seed = 3)

                    fakeGenerator.requests.map { it.language } shouldContainExactlyInAnyOrder listOf("Japanese", "English")
                }
            }

            `when`("생성과 저장 사이에 다른 쪽이 같은 리뷰를 고쳐 커밋하면") {
                then("그 리뷰만 failedIds 로 넘기고 나머지는 계속 저장한다") {
                    val ids = (1..3).map { review(bots[it - 1], food, long, "en") }
                    val contested = ids[1]
                    var holder: java.sql.Connection? = null
                    fakeGenerator.reply = {
                        if (fakeGenerator.requests.size == 2) {
                            holder = dataSource.connection.apply {
                                autoCommit = false
                                createStatement().use { st -> st.executeUpdate("UPDATE food_review SET content = 'Edited by someone else', version = version + 1 WHERE id = $contested") }
                            }
                            Thread { Thread.sleep(1500); holder!!.commit(); holder!!.close() }.start()
                        }
                        short
                    }

                    val result = rewriter.rewrite("dev", ratio = 1.0, dryRun = false, limit = 40, seed = 1)

                    result.failedIds shouldBe listOf(contested)
                    contentOf(ids[0]) shouldBe short
                    contentOf(contested) shouldBe "Edited by someone else"
                    contentOf(ids[2]) shouldBe short
                }
            }

            `when`("생성물이 끝내 상한을 넘으면") {
                then("그 리뷰는 failedIds 이고 원문을 그대로 둔다") {
                    val id = review(bots[0], food, long, "en")
                    fakeGenerator.reply = { long }

                    val result = rewriter.rewrite("dev", ratio = 1.0, dryRun = false, limit = 40, seed = 1)

                    result.failedIds shouldBe listOf(id)
                    result.rewritten shouldBe 0
                    contentOf(id) shouldBe long
                }
            }
        }

        given("재작성 어드민 API 가드") {
            fun call(target: String, body: Map<String, Any?>) = mockMvc.post("/api/admin/review-bots/reviews/rewrite") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
                header("X-Target-Environment", target)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }

            `when`("요청 환경이 서버 환경과 다르면") {
                then("409 REVIEW-006 이고 생성하지 않는다") {
                    review(bots[0], food, long, "en")
                    val body = call("prod", mapOf("ratio" to 0.5)).andExpect { status { isConflict() } }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    mapper.readTree(body).path("code").asText() shouldBe "REVIEW-006"
                    fakeGenerator.requests.size shouldBe 0
                }
            }

            `when`("ratio 가 0~1 밖이거나 limit 가 범위 밖이면") {
                then("400 COMMON-002 다") {
                    call("dev", mapOf("ratio" to 1.5)).andExpect { status { isBadRequest() } }
                    call("dev", mapOf("ratio" to 0.5, "limit" to 0)).andExpect { status { isBadRequest() } }
                    call("dev", mapOf("ratio" to 0.5, "limit" to 41)).andExpect { status { isBadRequest() } }
                }
            }

            `when`("seed 없이 실제 실행(dryRun=false)을 부르면") {
                then("400 이고 생성하지 않는다 — 나눠 부를 때 같은 표본을 보장할 수 없다") {
                    review(bots[0], food, long, "en")
                    call("dev", mapOf("ratio" to 0.5, "dryRun" to false)).andExpect { status { isBadRequest() } }
                    fakeGenerator.requests.size shouldBe 0
                }
            }

            `when`("기본값(dryRun=true)으로 부르면") {
                then("저장하지 않는다") {
                    val id = review(bots[0], food, long, "en")
                    call("dev", mapOf("ratio" to 1.0)).andExpect { status { isOk() } }
                    contentOf(id) shouldBe long
                }
            }
        }
    }
}
