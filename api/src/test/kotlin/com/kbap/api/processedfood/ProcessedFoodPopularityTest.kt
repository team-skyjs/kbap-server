package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDateTime
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodPopularityTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var popularity: ProcessedFoodPopularity

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String) = "'" + v.replace("'", "''") + "'"
        fun idOf(reportNo: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
        }
        fun seed(reportNo: String, name: String, maker: String, foodType: String = "과자") = execute(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, ${q(foodType)}, ${q(maker)}, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
        )
        fun seedCatalog() {
            seed("1984044800212", "빙그레 바나나맛 우유", "빙그레", "가공유")
            seed("1984044800213", "빙그레 딸기맛 우유", "빙그레", "가공유")
            seed("19870415003114", "초코파이", "오리온")
            seed("199204430031", "비락식혜", "팔도", "음료")
            seed("1992044300340", "밥알없는비락식혜", "팔도", "음료")
            seed("2016036417043", "치즈쿠키Ⅱ", "치즈제과")
            seed("2010046130335", "콘소메맛 팝콘", "팝콘제과")
            seed("20100461303274", "ONLY price 오리지널&카라멜 팝콘", "팝콘상회")
        }
        fun member(id: Long, bot: Boolean = false) {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            if (bot) execute("UPDATE member SET is_bot = 1 WHERE id = $id")
        }
        fun review(memberId: Long, productId: Long, at: LocalDateTime, rating: Int = 5) = execute(
            "INSERT INTO food_review (member_id, target_type, food_id, processed_food_id, rating, serving_speed_rating, staff_kindness_rating, content, status, created_at, updated_at, version) " +
                "VALUES ($memberId, 'PROCESSED_FOOD', NULL, $productId, $rating, 0, 0, '좋아요', 'ACTIVE', '$at', '$at', 0)",
        )
        fun list(token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods?size=50") {
                header("X-API-Version", "3.2")
                token?.let { header("Authorization", "Bearer $it") }
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun names() = list().path("items").map { it.path("nameKo").asText() }
        fun signalCount(productId: Long) = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM processed_food_signal WHERE processed_food_id = $productId").use { r -> r.next(); r.getLong(1) } }
        }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute("DELETE FROM processed_food_signal")
            seedCatalog()
            popularity.refresh()
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute("DELETE FROM processed_food_signal")
            popularity.refresh()
        }

        given("편의점 목록 앱 인기 점수(KB-939)") {
            `when`("앱 신호가 하나도 없으면") {
                then("점수를 계산해도 순서는 지금 시드 순서(KB-938) 그대로다") {
                    val before = names()
                    popularity.refresh()

                    val after = names()
                    after shouldContainExactly before
                    after.take(5) shouldContainExactly listOf("빙그레 바나나맛 우유", "빙그레 딸기맛 우유", "초코파이", "비락식혜", "밥알없는비락식혜")
                }
            }

            `when`("시드 밖 편의점 상품에 리뷰가 여러 날에 걸쳐 쌓이면") {
                then("앱 점수가 섞여 하위 시드 라인(밥알없는비락식혜)보다 앞으로 오지만 상위 시드(비락식혜)는 넘지 못한다") {
                    val cookie = idOf("2016036417043")
                    val now = LocalDateTime.now().withNano(0)
                    (1L..12L).forEach { n ->
                        member(93900 + n)
                        review(93900 + n, cookie, now.minusDays(n % 4))
                    }

                    popularity.refresh()
                    val shown = names()

                    (shown.indexOf("치즈쿠키Ⅱ") < shown.indexOf("밥알없는비락식혜")) shouldBe true
                    (shown.indexOf("치즈쿠키Ⅱ") > shown.indexOf("비락식혜")) shouldBe true
                }
            }

            `when`("리뷰가 편의점 목록 밖 같은 묶음 행(같은 이름·제조사, 다른 품목보고번호)에 쌓이면") {
                then("목록 상품 묶음의 점수로 합쳐 하위 시드 라인 위로 올린다") {
                    seed("2016036417999", "치즈쿠키Ⅱ", "치즈제과")
                    val sibling = idOf("2016036417999")
                    val now = LocalDateTime.now().withNano(0)
                    (1L..12L).forEach { n -> member(93930 + n); review(93930 + n, sibling, now.minusDays(n % 4)) }

                    popularity.refresh()
                    val shown = names()

                    (shown.indexOf("치즈쿠키Ⅱ") < shown.indexOf("밥알없는비락식혜")) shouldBe true
                }
            }

            `when`("리뷰·스캔 없이 조회만 여러 회원에게서 쌓이면") {
                then("조회 고유 사용자도 근거 수에 들어가 시드 순서에서 움직인다 — 하위 시드 라인 위로") {
                    val cookie = idOf("2016036417043")
                    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                    (1L..40L).forEach { n ->
                        member(94000 + n)
                        execute(
                            "INSERT INTO processed_food_signal (processed_food_id, member_id, signal_type, signal_date, status, created_at, updated_at) " +
                                "VALUES ($cookie, ${94000 + n}, 'VIEW', '${today.minusDays(n % 5)}', 'ACTIVE', NOW(6), NOW(6))",
                        )
                    }

                    popularity.refresh()
                    val shown = names()

                    (shown.indexOf("치즈쿠키Ⅱ") < shown.indexOf("밥알없는비락식혜")) shouldBe true
                }
            }

            `when`("같은 리뷰가 시드·봇·제외 회원(10·12·18)의 것이면") {
                then("집계하지 않아 순서가 바뀌지 않는다") {
                    val before = names()
                    val cookie = idOf("2016036417043")
                    val now = LocalDateTime.now().withNano(0)
                    listOf(10L, 12L, 18L).forEachIndexed { i, id -> member(id); review(id, cookie, now.minusDays(i.toLong())) }
                    (1L..9L).forEach { n -> member(93950 + n, bot = true); review(93950 + n, cookie, now.minusDays(n % 4)) }

                    popularity.refresh()

                    names() shouldContainExactly before
                }
            }

            `when`("하루에 리뷰가 몰려도 — 급등 보류 없음(예진 10/30 단순화)") {
                then("그대로 반영해 하위 시드 라인 위로 올린다") {
                    val cookie = idOf("2016036417043")
                    val now = LocalDateTime.now().withNano(0)
                    (1L..12L).forEach { n -> member(93970 + n); review(93970 + n, cookie, now) }

                    popularity.refresh()
                    val shown = names()

                    (shown.indexOf("치즈쿠키Ⅱ") < shown.indexOf("밥알없는비락식혜")) shouldBe true
                }
            }
        }

        given("가공식품 신호 적재(KB-939)") {
            `when`("회원이 같은 날 상세를 여러 번 보거나 비회원이 보면") {
                then("회원·상품·일 1행만 남고 비회원은 남지 않는다") {
                    val milk = idOf("1984044800212")
                    member(93999)
                    val token = tokenIssuer.issueAccessToken(93999, MemberRole.USER)
                    repeat(2) {
                        mockMvc.get("/api/processed-foods/$milk") { header("X-API-Version", "3.2"); header("Authorization", "Bearer $token") }
                            .andExpect { status { isOk() } }
                    }
                    mockMvc.get("/api/processed-foods/$milk") { header("X-API-Version", "3.2") }.andExpect { status { isOk() } }

                    signalCount(milk) shouldBe 1
                }
            }
        }
    }
}
