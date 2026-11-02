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
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodVerdictApiTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun seed(reportNo: String, name: String, allergens: String?, raw: String?): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '과자', '㈜테스트', ${q(raw)}, ${q(allergens)}, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun member(id: Long, vararg avoiding: String): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id, *avoiding)
            execute("UPDATE member SET scan_count = 0, scan_unlocked = 1 WHERE id = $id")
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun get(path: String, token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get(path) {
                header("X-API-Version", "3.2")
                token?.let { header("Authorization", "Bearer $it") }
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun post(path: String, body: Map<String, Any?>, token: String): JsonNode = mapper.readTree(
            mockMvc.post(path) {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("목록 요약의 표시 정보 유무(KB-947)") {
            `when`("원재료·알레르기 표시 유무가 다른 제품을 비회원·회원 목록과 이름 스캔 후보로 보면") {
                then("labelKnown 은 상세 labelSource 가 NONE 이 아닐 때만 true 다 — 둘 다 없거나 자리표시값·혼입 문장뿐이면 false") {
                    val expected = mapOf(
                        seed("LK-1", "표시확인 없음과자", null, "알수없음") to false,
                        seed("LK-2", "표시확인 원재료과자", null, "소맥분(밀), 설탕") to true,
                        seed("LK-3", "표시확인 알레르기과자", "밀, 대두 함유", null) to true,
                        seed("LK-4", "표시확인 혼입과자", "이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", "알수없음") to false,
                    )
                    val token = member(86947L, "PEANUT")
                    expected.forEach { (id, known) ->
                        (get("/api/processed-foods/$id").path("labelSource").asText() != "NONE") shouldBe known
                    }

                    listOf(get("/api/processed-foods?query=표시확인"), get("/api/processed-foods?query=표시확인", token)).forEach { page ->
                        page.path("items").associate { it.path("id").asLong() to it.path("labelKnown").asBoolean() } shouldBe expected
                    }
                    post("/api/processed-foods/scan/name", mapOf("ocrText" to "표시확인\n원재료과자"), token).path("candidates")
                        .filter { it.path("id").asLong() in expected }
                        .forEach { it.path("labelKnown").asBoolean() shouldBe expected.getValue(it.path("id").asLong()) }
                }
            }
        }

        given("확인 안 된 원재료 라벨 다국어(KB-936)") {
            `when`("회피 재료는 없지만 내용 불명 원재료가 있는 제품 상세를 lang=en 으로 보면") {
                then("SAFE_WITH_NOTE 이고 원문 토큰은 그대로, unclearIngredientLabels 는 맞은 사전 용어의 영어 라벨이다") {
                    val id = seed("UL-1", "라벨라면", null, "소맥분(밀), 복합조미식품(간장분말, 마늘), 시즈닝, 정제소금")

                    val detail = get("/api/processed-foods/$id?lang=en", member(93601L, "PEANUT"))

                    detail.path("verdict").asText() shouldBe "SAFE_WITH_NOTE"
                    detail.path("unclearIngredients").map { it.asText() }.any { it.startsWith("복합조미식품") } shouldBe true
                    detail.path("unclearIngredientLabels").map { it.path("term").asText() to it.path("label").asText() } shouldBe listOf(
                        "복합조미식품" to "Compound seasoning",
                        "시즈닝" to "Seasoning",
                    )
                }
            }

            `when`("lang 없이(한국어) 보면") {
                then("라벨은 한국어 용어 그대로다") {
                    val id = seed("UL-2", "라벨과자", null, "소맥분(밀), 혼합제제(구연산, 젖산)")

                    get("/api/processed-foods/$id", member(93602L, "PEANUT")).path("unclearIngredientLabels")
                        .map { it.path("term").asText() to it.path("label").asText() } shouldBe listOf("혼합제제" to "혼합제제")
                }
            }

            `when`("내용 불명 원재료가 없으면") {
                then("빈 배열이다") {
                    val id = seed("UL-3", "라벨우유", "우유 함유", "원유 100%")
                    get("/api/processed-foods/$id?lang=en", member(93603L, "PEANUT")).path("unclearIngredientLabels").size() shouldBe 0
                }
            }
        }

        given("가공식품 판정을 쓰는 응답(KB-866)") {
            `when`("우유 회피 회원과 비회원이 바나나맛우유 상세를 보면") {
                then("회원은 AVOID 와 근거, 비회원은 판정 null 과 주요 알레르기를 받고 unmapped 는 빈 배열이다") {
                    val id = seed("VD-1", "바나나맛 우유", "우유 함유", "원유(국산) 85%, 정제수, 설탕, 합성향료(바나나향)")

                    val mine = get("/api/processed-foods/$id", member(86601L, "MILK"))
                    mine.path("verdict").asText() shouldBe "AVOID"
                    mine.path("avoidMatches")[0].path("code").asText() shouldBe "MILK"
                    mine.path("unclearIngredients").size() shouldBe 0
                    mine.path("unmapped").size() shouldBe 0
                    mine.path("detections").map { it.path("code").asText() } shouldBe listOf("MILK")

                    val guest = get("/api/processed-foods/$id")
                    guest.path("verdict").isNull shouldBe true
                    guest.path("majorAllergens").map { it.asText() } shouldBe listOf("MILK")
                }
            }
            `when`("알레르기 표시에 혼입 문장이 섞인 제품 상세를 땅콩 회피 회원이 보면 — Codex P1(#458)") {
                then("검출·회피 대조·주요 알레르기도 판정과 같은 분리 판독을 써서 땅콩이 검출로 나오지 않는다") {
                    val id = seed("VD-3", "땅콩공장 쿠키", "밀 함유. 이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", "소맥분(밀), 설탕")

                    val p = get("/api/processed-foods/$id", member(86605L, "PEANUT"))
                    p.path("verdict").asText() shouldBe "CAUTION"
                    p.path("detections").map { it.path("code").asText() } shouldBe listOf("WHEAT")
                    p.path("avoidance")[0].path("match").asText() shouldBe "UNCONFIRMED"
                    p.path("majorAllergens").map { it.asText() } shouldBe listOf("WHEAT")
                }
            }
            `when`("혼입 문장이 있는 제품 상세를 비회원·다른 회피 회원이 보면 — 공부 7R") {
                then("회원 여부와 무관한 mayContain 으로 혼입 재료를 받는다") {
                    val id = seed("VD-5", "땅콩공장 비스킷", "밀 함유. 이 제품은 땅콩을 사용한 제품과 같은 시설에서 제조합니다.", "소맥분(밀), 설탕")

                    val guest = get("/api/processed-foods/$id")
                    guest.path("majorAllergens").map { it.asText() } shouldBe listOf("WHEAT")
                    guest.path("mayContain").map { it.path("code").asText() } shouldBe listOf("PEANUT")
                    guest.path("mayContainMatches").size() shouldBe 0

                    val wheatAvoider = get("/api/processed-foods/$id", member(86606L, "WHEAT"))
                    wheatAvoider.path("mayContain").map { it.path("code").asText() } shouldBe listOf("PEANUT")
                    wheatAvoider.path("mayContainMatches").size() shouldBe 0
                }
            }
            `when`("원재료가 자리표시값이고 알레르기 표시도 없으면 — Codex P2(#458)") {
                then("labelSource 는 NONE 이다") {
                    val id = seed("VD-4", "모르는 과자", null, "알수없음")
                    get("/api/processed-foods/$id").path("labelSource").asText() shouldBe "NONE"
                }
            }
            `when`("땅콩 회피 회원이 목록과 이름 스캔 후보를 보면") {
                then("항목마다 판정이 붙고 비회원 목록은 판정이 null 이다") {
                    seed("VD-2", "땅콩공장 크래커", "밀, 대두 함유. 이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", "소맥분(밀), 대두유, 설탕")
                    val token = member(86602L, "PEANUT")

                    get("/api/processed-foods?query=크래커", token).path("items")[0].path("verdict").asText() shouldBe "CAUTION"
                    get("/api/processed-foods?query=크래커").path("items")[0].path("verdict").isNull shouldBe true
                    post("/api/processed-foods/scan/name", mapOf("ocrText" to "땅콩공장\n크래커"), token)
                        .path("candidates")[0].path("verdict").asText() shouldBe "CAUTION"
                }
            }
            `when`("땅콩 회피 회원이 성분표를 분석하면") {
                then("같은 규칙으로 CAUTION 과 혼입 가능 근거를 받고 unmapped 는 빈 배열이다") {
                    val result = post(
                        "/api/processed-foods/scan/label",
                        mapOf(
                            "analysisId" to null,
                            "clientRequestId" to UUID.randomUUID().toString(),
                            "pages" to listOf(
                                mapOf("ocrText" to "원재료명: 소맥분(밀), 새우 10%, 설탕, 식물성유지\n알레르기 유발물질: 밀, 새우 함유"),
                                mapOf("ocrText" to "이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다."),
                            ),
                            "lang" to "ko",
                        ),
                        member(86603L, "PEANUT"),
                    )
                    result.path("verdict").asText() shouldBe "CAUTION"
                    result.path("mayContainMatches")[0].path("code").asText() shouldBe "PEANUT"
                    result.path("unmapped").size() shouldBe 0
                }
            }
            `when`("성분표 OCR 원재료 줄에 시설 문구가 붙어 있으면 — Codex P1(#459)") {
                then("앞 원재료는 확정으로 읽어 밀 회피가 AVOID 다") {
                    val result = post(
                        "/api/processed-foods/scan/label",
                        mapOf(
                            "analysisId" to null,
                            "clientRequestId" to UUID.randomUUID().toString(),
                            "pages" to listOf(mapOf("ocrText" to "원재료명: 소맥분, 설탕, 본 제품은 땅콩을 사용한 제품과 같은 생산라인에서 생산합니다")),
                            "lang" to "ko",
                        ),
                        member(86608L, "WHEAT"),
                    )
                    result.path("verdict").asText() shouldBe "AVOID"
                }
            }
            `when`("성분표 원재료 줄이 자리표시값이고 혼입 문장만 읽히면 — Codex P2(#459)") {
                then("labelSource 는 INGREDIENTS_ONLY 가 아니라 NONE 이다(상세와 같은 규칙)") {
                    val result = post(
                        "/api/processed-foods/scan/label",
                        mapOf(
                            "analysisId" to null,
                            "clientRequestId" to UUID.randomUUID().toString(),
                            "pages" to listOf(mapOf("ocrText" to "원재료명: 알수없음\n이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.")),
                            "lang" to "ko",
                        ),
                        member(86609L, "PEANUT"),
                    )
                    result.path("labelSource").asText() shouldBe "NONE"
                    result.path("verdict").asText() shouldBe "CAUTION"
                }
            }
            `when`("성분표가 혼입 문구뿐이면 — Codex P1") {
                then("읽을 수 없음으로 거절하지 않고 같은 규칙으로 CAUTION 을 낸다") {
                    val result = post(
                        "/api/processed-foods/scan/label",
                        mapOf(
                            "analysisId" to null,
                            "clientRequestId" to UUID.randomUUID().toString(),
                            "pages" to listOf(mapOf("ocrText" to "알레르기 유발물질: 땅콩 혼입 가능")),
                            "lang" to "ko",
                        ),
                        member(86607L, "PEANUT"),
                    )
                    result.path("verdict").asText() shouldBe "CAUTION"
                }
            }
            `when`("성분표 원재료 줄이 머리말 + 자리표시값뿐이면 — Codex P1") {
                then("머리말을 뗀 값으로 판정해 의무 알레르기 밖 회피(마늘)에 SAFE 를 내지 않는다") {
                    val result = post(
                        "/api/processed-foods/scan/label",
                        mapOf(
                            "analysisId" to null,
                            "clientRequestId" to UUID.randomUUID().toString(),
                            "pages" to listOf(mapOf("ocrText" to "원재료명: 알수없음\n알레르기 유발물질: 밀, 대두 함유")),
                            "lang" to "ko",
                        ),
                        member(86604L, "GARLIC"),
                    )
                    result.path("verdict").isNull shouldBe true
                }
            }
        }
    }
}
