package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.ingredient.AvoidanceGroupMatrix
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun seedProduct(
            reportNo: String,
            name: String,
            manufacturer: String? = null,
            allergens: String? = null,
            raw: String? = null,
            imageUrl: String? = null,
            seenDaysAgo: Int = 0,
            seller: String? = null,
            status: String = "ACTIVE",
            seenHoursAgo: Int = seenDaysAgo * 24,
        ): Long {
            fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
            val normalized = name.replace(Regex("[\\s()·]|오리지널"), "")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, seller, raw_materials, allergens, " +
                    "image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) VALUES " +
                    "(${q(reportNo)}, ${q(name)}, ${q(normalized)}, '과자', ${q(manufacturer)}, ${q(seller)}, ${q(raw)}, ${q(allergens)}, ${q(imageUrl)}, 'HACCP', " +
                    "REPEAT('a', 64), NOW(6) - INTERVAL $seenHoursAgo HOUR, NOW(6) - INTERVAL $seenHoursAgo HOUR, '$status', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM processed_food_product WHERE report_no = ?").use { ps ->
                    ps.setString(1, reportNo)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }

        fun get(path: String, version: String = "3.2", token: String? = null, expected: Int = 200): JsonNode =
            mapper.readTree(
                mockMvc.get(path) {
                    header("X-API-Version", version)
                    token?.let { header("Authorization", "Bearer $it") }
                }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            )

        fun seedMirrorRun(id: Long, exitCode: String, startedHoursAgo: Int) {
            execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES ($id, 0, 'processedFoodMirrorJob', 'pf-$id')")
            execute(
                "INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) " +
                    "VALUES ($id, 0, $id, NOW(6) - INTERVAL $startedHoursAgo HOUR, NOW(6) - INTERVAL $startedHoursAgo HOUR, NOW(6), 'COMPLETED', '$exitCode', NOW(6))",
            )
        }

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID IN (990001, 990002)")
            execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID IN (990001, 990002)")
            seedMirrorRun(990001, "COMPLETED", startedHoursAgo = 24)
            seedMirrorRun(990002, "PARTIAL", startedHoursAgo = 0)
        }
        afterSpec { execute("DELETE FROM processed_food_product") }

        given("가공식품 검색 — GET /api/processed-foods?query=") {
            seedProduct("PF-S-1", "새우깡", "농심_구미공장")
            seedProduct("PF-S-2", "새우깡블랙", "농심_구미공장")
            seedProduct("PF-S-3", "매운새우깡", "농심_구미공장")
            seedProduct("PF-S-4", "신라면", "알수없음")
            seedProduct("PF-S-5", "신라면블랙", "농심_부산공장")
            seedProduct("PF-S-6", "포카칩오리지널", "오리온_청주시")
            seedProduct("PF-S-7", "단종된새우깡", "농심", seenDaysAgo = 10)
            seedProduct("PF-S-8", "100%_새우깡", "농심")

            fun names(json: JsonNode) = json.path("payload").path("items").map { it.path("productName").asText() }

            `when`("제품명을 그대로 치면") {
                then("완전 일치 → 접두 → 포함 순이고, 생산종료(최근 미러보다 3일 넘게 안 보인 제품)는 빠진다") {
                    names(get("/api/processed-foods?query=새우깡")) shouldBe listOf("새우깡", "새우깡블랙", "매운새우깡", "100%_새우깡")
                }
            }
            `when`("제조사와 제품명을 함께 치면") {
                then("제조사 토큰은 제조사에, 나머지는 제품명에 맞춘다 — 제조사가 알수없음이어도 이름이 같으면 맨 앞") {
                    names(get("/api/processed-foods?query=농심 신라면")) shouldBe listOf("신라면", "신라면블랙")
                }
            }
            `when`("공백·'오리지널'을 섞어 치면") {
                then("미러와 같은 정규화로 맞춘다") {
                    names(get("/api/processed-foods?query=포카칩 오리지널")) shouldBe listOf("포카칩오리지널")
                }
            }
            `when`("% 나 _ 를 치면") {
                then("와일드카드가 아니라 글자로 찾는다") {
                    names(get("/api/processed-foods?query=100%_")) shouldBe listOf("100%_새우깡")
                }
            }
            `when`("size 보다 결과가 많으면") {
                then("nextCursor 로 이어 받으면 겹치지 않고 전부 이어진다") {
                    val first = get("/api/processed-foods?query=새우깡&size=2")
                    names(first) shouldBe listOf("새우깡", "새우깡블랙")
                    first.path("payload").path("hasNext").asBoolean() shouldBe true
                    val cursor = first.path("payload").path("nextCursor").asText()
                    val second = get("/api/processed-foods?query=새우깡&size=2&cursor=$cursor")
                    names(second) shouldBe listOf("매운새우깡", "100%_새우깡")
                    second.path("payload").path("hasNext").asBoolean() shouldBe false
                }
            }
            `when`("검색어가 길거나 토큰이 많으면 — 토큰마다 쿼리가 돌아 증폭되지 않게") {
            then("100자 초과는 400, 토큰은 앞 5개만 쓴다") {
                    get("/api/processed-foods?query=" + "가".repeat(101), expected = 400)
                    names(get("/api/processed-foods?query=새우깡 zq1 zq2 zq3 zq4 포카칩")) shouldBe listOf("새우깡", "새우깡블랙", "매운새우깡", "100%_새우깡")
                }
            }
            `when`("흔한 토큰이라 후보가 상한(300)을 넘고 완전 일치 제품의 id 가 뒤이면 — 공부 세션 [막음]") {
                then("상한으로 자르기 전에 등급순으로 골라 완전 일치가 1등이다") {
                    execute(
                        "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 310) " +
                            "SELECT CONCAT('PF-C-', i), CONCAT('딸기우유과자', i), CONCAT('딸기우유과자', i), '과자', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6) FROM n",
                    )
                    seedProduct("PF-C-EXACT", "우유")
                    names(get("/api/processed-foods?query=우유&size=1")) shouldBe listOf("우유")
                }
            }
            `when`("표시 제조사를 구두점째 치거나 판매원만 있는 제품의 판매원을 치면 — Codex P2") {
                then("그 제품이 후보에 들어온다") {
                    seedProduct("PF-M-1", "코크제로", "코카-콜라음료㈜ 경남 양산시 충렬로 269")
                    seedProduct("PF-M-2", "무명과자", "알수없음", seller = "㈜해태제과/서울특별시")
                    names(get("/api/processed-foods?query=코카-콜라음료")) shouldBe listOf("코크제로")
                    names(get("/api/processed-foods?query=코카콜라음료")) shouldBe listOf("코크제로")
                    names(get("/api/processed-foods?query=해태제과")) shouldBe listOf("무명과자")
                }
            }
            `when`("커서가 후보 상한을 넘는 큰 수이면 — Codex P2(오버플로)") {
                then("400 FOOD-002 로 거부한다") {
                    get("/api/processed-foods?query=새우깡&cursor=2147483647", expected = 400).path("code").asText() shouldBe "FOOD-002"
                }
            }
            `when`("제조사만 맞는 제품이 상한(300)을 넘고 짧은 이름 제품의 id 가 뒤이면 — Codex P2") {
                then("상한으로 자르기 전에 짧은 이름 순으로 골라 그 제품이 1등이다") {
                    execute(
                        "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 305) " +
                            "SELECT CONCAT('PF-MK-', i), CONCAT('긴이름상품번호', i), CONCAT('긴이름상품번호', i), '과자', '대량제조㈜', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6) FROM n",
                    )
                    seedProduct("PF-MK-SHORT", "짧은", "대량제조㈜")
                    names(get("/api/processed-foods?query=대량제조&size=1")) shouldBe listOf("짧은")
                }
            }
            `when`("검색어가 비면") {
                then("검색어 없는 목록으로 200 이다(KB-867 — 종전 400 FOOD-003)") {
                    get("/api/processed-foods?query=").path("success").asBoolean() shouldBe true
                    get("/api/processed-foods?query=   ").path("success").asBoolean() shouldBe true
                }
            }
            `when`("구버전 헤더로 부르면") {
                then("가공식품 경로는 3.2 미만에서 열리지 않는다") {
                    get("/api/processed-foods?query=새우깡", version = "3.1", expected = 404)
                }
            }
        }

        given("가공식품 상세 회피 대조 — 묶음 4종·젤라틴·고기 육수(KB-842)") {
            fun avoidanceOf(memberId: Long, productId: Long): Map<String, JsonNode> =
                get("/api/processed-foods/$productId", token = tokenIssuer.issueAccessToken(memberId, MemberRole.USER))
                    .path("payload").path("avoidance").associate { it.path("code").asText() to it }

            `when`("묶음만 피하는 회원이 하위 재료가 원재료에 적힌 제품을 보면") {
                then("하위 재료마다 묶음이 DETECTED 이다") {
                    val groups = AvoidanceGroupMatrix.rows.map { it.group }.distinct()
                    groups.forEachIndexed { i, group -> FoodTestSeed.seedMemberAvoiding(dataSource, 88420L + i, group.name) }
                    AvoidanceGroupMatrix.rows.mapIndexedNotNull { i, row ->
                        val productId = seedProduct("PF-G-$i", "묶음제품$i", allergens = "알수없음", raw = "${row.labelTerm}, 설탕")
                        val match = avoidanceOf(88420L + groups.indexOf(row.group), productId)[row.group.name]?.path("match")?.asText()
                        "${row.group}→${row.member}(${row.labelTerm})=$match".takeIf { match != "DETECTED" }
                    } shouldBe emptyList()
                }
            }
            `when`("해산물을 피하는 회원이 '갑각류'·'연체류'만 적힌 제품을 보면 — 공부 막음") {
                then("둘 다 DETECTED(R-100 — 해산물이 새 묶음을 함의)") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88432L, "SEAFOOD")
                    listOf("갑각류", "연체류").mapIndexed { i, term ->
                        avoidanceOf(88432L, seedProduct("PF-G-SF-$i", "해산물스낵$i", allergens = "알수없음", raw = "$term, 설탕")).getValue("SEAFOOD").path("match").asText()
                    } shouldBe listOf("DETECTED", "DETECTED")
                }
            }
            `when`("돼지고기를 피하는 회원이 젤라틴 제품을 보면") {
                then("유래 표기 없는 젤라틴은 DETECTED, 어류·식물성 젤라틴은 UNCONFIRMED(확인 필요)") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88430L, "PORK")
                    avoidanceOf(88430L, seedProduct("PF-G-GEL-1", "젤리A", allergens = "알수없음", raw = "설탕, 젤라틴"))
                        .getValue("PORK").path("match").asText() shouldBe "DETECTED"
                    avoidanceOf(88430L, seedProduct("PF-G-GEL-2", "젤리B", allergens = "알수없음", raw = "설탕, 어류 젤라틴"))
                        .getValue("PORK").path("match").asText() shouldBe "UNCONFIRMED"
                    avoidanceOf(88430L, seedProduct("PF-G-GEL-3", "젤리C", allergens = "알수없음", raw = "설탕, 식물성젤라틴"))
                        .getValue("PORK").path("match").asText() shouldBe "UNCONFIRMED"
                }
            }
            `when`("돼지 유래를 명시한 젤라틴 제품을 보면 — S-845") {
                then("돼지 회피·젤라틴 직접 회피 모두 DETECTED, 소고기만 피하는 회원도 DETECTED(과경고 감수 — 설계 결정)") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88433L, "BEEF")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88434L, "PORK")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88435L, "GELATIN")
                    val product = seedProduct("PF-G-GEL-4", "젤리D", allergens = "알수없음", raw = "설탕, 돈피젤라틴")
                    avoidanceOf(88434L, product).getValue("PORK").path("match").asText() shouldBe "DETECTED"
                    avoidanceOf(88435L, product).getValue("GELATIN").path("match").asText() shouldBe "DETECTED"
                    avoidanceOf(88433L, product).getValue("BEEF").path("match").asText() shouldBe "DETECTED"
                }
            }
            `when`("육수를 피하는 회원이 사골 육수·사골 제품을 보면") {
                then("둘 다 DETECTED") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88431L, "BROTH")
                    avoidanceOf(88431L, seedProduct("PF-G-BR-1", "곰탕A", allergens = "알수없음", raw = "사골 육수, 정제소금"))
                        .getValue("BROTH").path("match").asText() shouldBe "DETECTED"
                    avoidanceOf(88431L, seedProduct("PF-G-BR-2", "곰탕B", allergens = "알수없음", raw = "사골농축액, 정제소금"))
                        .getValue("BROTH").path("match").asText() shouldBe "DETECTED"
                }
            }
        }

        given("가공식품 상세 — GET /api/processed-foods/{id}") {
            val declared = seedProduct(
                "PF-D-1", "새우버터칩", "농심_구미공장",
                allergens = "새우, 우유 함유", raw = "소맥분(밀:미국산), 가공버터{유지방(우유)}, 버터향", imageUrl = "http://img/1.jpg",
            )
            val ingredientsOnly = seedProduct("PF-D-2", "땅콩강정", "알수없음", allergens = "알수없음", raw = "땅콩, 물엿")
            val haccpImage = seedProduct("PF-D-7", "에이치칩", "농심", imageUrl = "http://www.haccp.or.kr/fresh/prdimg/7/7-1.jpg")
            val none = seedProduct("PF-D-3", "물", "알수없음", allergens = "알수없음", raw = null)
            val stale = seedProduct("PF-D-4", "단종칩", "농심", seenDaysAgo = 10)
            val deleted = seedProduct("PF-D-5", "삭제칩", "농심", status = "DELETED")
            val partialGap = seedProduct("PF-D-6", "부분실패칩", "농심", seenHoursAgo = 84)

            `when`("비회원이 알레르기 표시가 있는 제품을 보면") {
                then("원문·표시 출처 구분·검출·근거·출처 고지 키·이미지가 오고 회원 대조는 null 이다") {
                    val p = get("/api/processed-foods/$declared").path("payload")
                    p.path("productName").asText() shouldBe "새우버터칩"
                    p.path("maker").asText() shouldBe "농심"
                    p.path("rawMaterials").asText() shouldBe "소맥분(밀:미국산), 가공버터{유지방(우유)}, 버터향"
                    p.path("allergens").asText() shouldBe "새우, 우유 함유"
                    p.path("labelSource").asText() shouldBe "DECLARED"
                    p.path("detections").map { it.path("code").asText() + ":" + it.path("source").asText() } shouldBe
                        listOf("SHRIMP:ALLERGEN_DECLARATION", "MILK:ALLERGEN_DECLARATION", "WHEAT:RAW_MATERIALS", "BUTTER:RAW_MATERIALS")
                    p.path("detections").first { it.path("code").asText() == "WHEAT" }.path("evidence").asText() shouldBe "소맥분"
                    p.path("source").path("provider").asText() shouldBe "HACCP"
                    p.path("source").path("attributionKey").asText() shouldBe "processedFood.source.haccp"
                    p.path("source").has("updatedAt") shouldBe false
                    p.path("image").path("url").asText() shouldBe "http://img/1.jpg"
                    p.path("image").path("attributionKey").asText() shouldBe "processedFood.image.haccp"
                    p.path("avoidance").isNull shouldBe true
                    p.path("unmapped").size() shouldBe 0
                }
            }
            `when`("우유·땅콩을 피하는 회원이 보면") {
                then("우유는 DETECTED(근거와 함께), 땅콩은 UNCONFIRMED — '없음/안전' 값은 없다") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88101L, "MILK", "PEANUT")
                    val token = tokenIssuer.issueAccessToken(88101L, MemberRole.USER)
                    val raw = get("/api/processed-foods/$declared", token = token)
                    val avoidance = raw.path("payload").path("avoidance").associate { it.path("code").asText() to it }
                    avoidance.getValue("MILK").path("match").asText() shouldBe "DETECTED"
                    avoidance.getValue("MILK").path("matchedCodes").map { it.asText() }.toSet() shouldBe setOf("MILK", "BUTTER")
                    avoidance.getValue("PEANUT").path("match").asText() shouldBe "UNCONFIRMED"
                    raw.toString() shouldNotContain "SAFE"
                }
            }
            `when`("해산물(카테고리)을 피하는 회원이 새우가 검출된 제품을 보면 — R-100") {
                then("DETECTED 이고 matchedCodes 에 새우가 담긴다(어느 하위 재료로 걸렸는지)") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88102L, "SEAFOOD")
                    val token = tokenIssuer.issueAccessToken(88102L, MemberRole.USER)
                    val match = get("/api/processed-foods/$declared", token = token).path("payload").path("avoidance").single()
                    match.path("code").asText() shouldBe "SEAFOOD"
                    match.path("match").asText() shouldBe "DETECTED"
                    match.path("matchedCodes").map { it.asText() } shouldBe listOf("SHRIMP")
                }
            }
            `when`("새우(하위)만 피하는 회원이 '해산물'만 적힌 제품을 보면 — 반대 방향") {
                then("DETECTED 가 아니라 UNCONFIRMED 이다") {
                    val seafoodOnly = seedProduct("PF-D-8", "해산물스낵", "농심", allergens = "알수없음", raw = "해산물분말, 설탕")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88103L, "SHRIMP")
                    val token = tokenIssuer.issueAccessToken(88103L, MemberRole.USER)
                    get("/api/processed-foods/$seafoodOnly", token = token).path("payload").path("avoidance").single().path("match").asText() shouldBe "UNCONFIRMED"
                }
            }
            `when`("알레르기 표시가 '알수없음'이면") {
                then("원재료가 있으면 INGREDIENTS_ONLY, 둘 다 없으면 NONE 이다") {
                    get("/api/processed-foods/$ingredientsOnly").path("payload").path("labelSource").asText() shouldBe "INGREDIENTS_ONLY"
                    get("/api/processed-foods/$none").path("payload").let {
                        it.path("labelSource").asText() shouldBe "NONE"
                        it.path("image").isNull shouldBe true
                    }
                }
            }
            `when`("DB 에 http 로 저장된 HACCP 이미지이면 — 앱 P-503(iOS ATS·Android cleartext)") {
                then("상세·검색 응답에서 https 로 바꿔 준다(DB 값은 그대로)") {
                    get("/api/processed-foods/$haccpImage").path("payload").path("image").path("url").asText() shouldBe
                        "https://www.haccp.or.kr/fresh/prdimg/7/7-1.jpg"
                    get("/api/processed-foods?query=에이치칩").path("payload").path("items").single().path("imageUrl").asText() shouldBe
                        "https://www.haccp.or.kr/fresh/prdimg/7/7-1.jpg"
                }
            }
            `when`("없는 id 나 생산종료 제품이면") {
                then("404 PFOOD-001 · 410 PFOOD-002") {
                    get("/api/processed-foods/999999999", expected = 404).path("code").asText() shouldBe "PFOOD-001"
                    get("/api/processed-foods/$stale", expected = 410).path("code").asText() shouldBe "PFOOD-002"
                    get("/api/processed-foods/$deleted", expected = 404).path("code").asText() shouldBe "PFOOD-001"
                }
            }
            `when`("마지막 성공 미러 뒤에 부분 실패 실행만 있으면 — 공부 세션 [지적 1]") {
                then("기준은 성공 실행 시각이라 성공 실행에서 본 제품은 생산종료가 아니다") {
                    get("/api/processed-foods/$partialGap").path("payload").path("productName").asText() shouldBe "부분실패칩"
                }
            }
        }
    }
}
