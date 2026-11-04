package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodSameNameGroupTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun seed(reportNo: String, name: String, maker: String, capacity: String? = null, coverage: String? = null, status: String? = null, raw: String = "옥수수, 바나나", supplement: String? = null): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, maker_supplement, capacity, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, cutout_coverage, cutout_review_status, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '과자', ${q(maker)}, ${q(supplement)}, ${q(capacity)}, ${q(raw)}, NULL, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), ${coverage ?: "NULL"}, ${q(status)}, 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun get(path: String, token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get(path) { header("X-API-Version", "3.2"); token?.let { header("Authorization", "Bearer $it") } }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun reportNos(page: JsonNode): List<String> = page.path("items").map { it.path("reportNo").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("용량·용기만 다른 상품도 한 묶음(예진 11/2)") {
            `when`("신라면·신라면컵·신라면 큰사발과 같은 제조사의 다른 상품이 있으면") {
                then("검색·편의점 목록에서 신라면 묶음은 1건이고, 상세의 같은 상품 목록에 셋이 다 있으며, 다른 상품은 따로 나온다") {
                    val bag = seed("SV-1", "신라면", "농심")
                    val cup = seed("SV-2", "신라면컵", "농심")
                    val bowl = seed("SV-3", "신라면 큰사발", "농심")
                    seed("SV-4", "신라면 블랙", "농심")

                    val found = reportNos(get("/api/processed-foods?query=신라면"))
                    found.count { it in setOf("SV-1", "SV-2", "SV-3") } shouldBe 1
                    found.contains("SV-4") shouldBe true
                    reportNos(get("/api/processed-foods?size=50")).count { it in setOf("SV-1", "SV-2", "SV-3") } shouldBe 1
                    get("/api/processed-foods/$cup").path("sameNameProducts").map { it.path("id").asLong() }.sorted() shouldBe listOf(bag, cup, bowl).sorted()
                }
            }
            `when`("신라면 앞면을 이름 스캔하면 — Codex P2(#530)") {
                then("후보에 신라면 묶음은 1건이다(카탈로그 부스트 행과 같은 묶음 변형이 따로 나오지 않는다)") {
                    seed("SV-1", "신라면", "농심")
                    seed("SV-2", "신라면컵", "농심")
                    seed("SV-3", "신라면 큰사발", "농심")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 95101L)
                    val token = tokenIssuer.issueAccessToken(95101L, MemberRole.USER)
                    val candidates = mapper.readTree(
                        mockMvc.post("/api/processed-foods/scan/name") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer $token")
                            contentType = org.springframework.http.MediaType.APPLICATION_JSON
                            content = mapper.writeValueAsString(mapOf("ocrText" to "농심\n신라면"))
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("candidates").map { it.path("reportNo").asText() }
                    candidates.count { it in setOf("SV-1", "SV-2", "SV-3") } shouldBe 1
                }
            }
        }

        given("같은 이름 상품을 대표 1개로 묶기(KB-950)") {
            `when`("용량만 다른 바나나킥 두 행이 있으면(dev 실측 report 2001054954151 140g 0.20 / 199104611019 75g 0.71)") {
                then("검색 결과에 바나나킥은 1건이고, 둘 다 편의점 같은 대표 라인이라 누끼 비율이 높은 75g 이 대표다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")
                    seed("TEST-BM-1", "바나나우유", "테스트유업")

                    val found = reportNos(get("/api/processed-foods?query=바나나"))

                    found.count { it == "2001054954151" || it == "199104611019" } shouldBe 1
                    found.contains("199104611019") shouldBe true
                    found.contains("TEST-BM-1") shouldBe true
                }
            }
            `when`("검색어 없는 편의점 목록에서도") {
                then("바나나킥은 1건이고 같은 규칙(승인 → 누끼 비율)으로 대표를 고른다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000", "APPROVED")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    val shown = reportNos(get("/api/processed-foods?size=50"))

                    shown.count { it == "2001054954151" || it == "199104611019" } shouldBe 1
                    shown.contains("2001054954151") shouldBe true
                }
            }
            `when`("dev 실측처럼 한 행은 제조사 '알수없음'·보완값 없음, 다른 행은 보완값 농심이면") {
                then("제조사 미상 행이 같은 이름의 유일한 제조사 묶음(농심)에 합쳐져 바나나킥은 1건, 상세 형제도 2건이다") {
                    val big = seed("2001054954151", "바나나킥", "알수없음", "140g", "0.2000")
                    val small = seed("199104611019", "바나나킥", "알수없음", "75g", "0.7100", supplement = "농심")

                    val found = reportNos(get("/api/processed-foods?query=바나나"))
                    found.count { it == "2001054954151" || it == "199104611019" } shouldBe 1
                    found.contains("199104611019") shouldBe true
                    get("/api/processed-foods/$big").path("sameNameProducts").map { it.path("id").asLong() } shouldBe listOf(big, small).sorted()
                }
            }
            `when`("제조사 미상 행과 같은 이름의 제조사 묶음이 둘 이상이면") {
                then("어느 쪽인지 모르므로 합치지 않는다 — 셋 다 따로 보인다") {
                    seed("TEST-AMB-0", "모호과자", "알수없음")
                    seed("TEST-AMB-1", "모호과자", "가나제과")
                    seed("TEST-AMB-2", "모호과자", "다라제과")

                    reportNos(get("/api/processed-foods?query=모호과자")) shouldContainExactlyInAnyOrder listOf("TEST-AMB-0", "TEST-AMB-1", "TEST-AMB-2")
                }
            }
            `when`("묶음 안에 누끼 승인(APPROVED) 행이 있으면") {
                then("누끼 비율보다 승인이 먼저다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000", "APPROVED")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    reportNos(get("/api/processed-foods?query=바나나킥")) shouldBe listOf("2001054954151")
                }
            }
            `when`("묶음 안에 편의점 대표 라인 밖 행이 있으면") {
                then("누끼 비율이 더 높아도 대표 라인 안 행이 먼저다") {
                    seed("TEST-BK-OUT", "바나나킥", "농심", "45g", "0.9900")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    reportNos(get("/api/processed-foods?query=바나나킥")) shouldBe listOf("199104611019")
                }
            }
            `when`("승인·라인·누끼 비율이 같으면") {
                then("최근 미러에서 본 행이 먼저(KB-858 유지), 그것도 같으면 id 가 작은 쪽이 대표다") {
                    val stale = seed("TEST-SEEN-1", "최근과자", "테스트제과", "50g")
                    val fresh = seed("TEST-SEEN-2", "최근과자", "테스트제과", "100g")
                    execute("UPDATE processed_food_product SET last_seen_at = NOW(6) - INTERVAL 1 DAY WHERE id = $stale")
                    get("/api/processed-foods?query=최근과자").path("items").map { it.path("id").asLong() } shouldBe listOf(fresh)

                    val first = seed("TEST-SAME-1", "같은이름과자", "테스트제과", "50g")
                    seed("TEST-SAME-2", "같은이름과자", "테스트제과", "100g")
                    execute("UPDATE processed_food_product SET last_seen_at = NOW(6) - INTERVAL 1 HOUR WHERE report_no IN ('TEST-SAME-1', 'TEST-SAME-2')")

                    get("/api/processed-foods?query=같은이름과자").path("items").map { it.path("id").asLong() } shouldBe listOf(first)
                }
            }
            `when`("묶음이 여럿인 검색을 한 건씩 커서로 넘기면") {
                then("경계에서 같은 묶음이 두 번 나오거나 빠지지 않는다") {
                    (1..4).forEach { n ->
                        seed("TEST-PG-$n-A", "페이지과자$n", "테스트제과", "50g")
                        seed("TEST-PG-$n-B", "페이지과자$n", "테스트제과", "100g")
                    }

                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    do {
                        val page = get("/api/processed-foods?query=페이지과자&size=1" + (cursor?.let { "&cursor=$it" } ?: ""))
                        seen += page.path("items").map { it.path("productName").asText() }
                        cursor = page.path("nextCursor").takeUnless { it.isNull || it.isMissingNode }?.asText()
                    } while (cursor != null)

                    seen shouldContainExactlyInAnyOrder (1..4).map { "페이지과자$it" }
                }
            }
            `when`("안전도 필터를 쓰면") {
                then("대표가 아니라 필터를 통과한 묶음 행 중에서 대표를 고른다 — 대표가 걸러져도 통과한 형제가 남는다") {
                    seed("TEST-VF-A", "필터과자", "테스트제과", "50g", "0.9000", "APPROVED", raw = "소맥분(밀), 땅콩")
                    seed("TEST-VF-B", "필터과자", "테스트제과", "100g", "0.1000", raw = "소맥분(밀), 설탕")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 95001L, "PEANUT")
                    val token = tokenIssuer.issueAccessToken(95001L, MemberRole.USER)

                    reportNos(get("/api/processed-foods?query=필터과자&verdict=SAFE", token)) shouldBe listOf("TEST-VF-B")
                    reportNos(get("/api/processed-foods?query=필터과자", token)) shouldBe listOf("TEST-VF-A")
                }
            }
            `when`("검색어 없는 편의점 목록에서 시드 라인 대표가 안전도 필터에 걸리면 — Codex P2(#516)") {
                then("같은 묶음의 통과한 형제가 그 라인의 대표로 보인다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000", "APPROVED", raw = "옥수수, 땅콩")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 95002L, "PEANUT")
                    val token = tokenIssuer.issueAccessToken(95002L, MemberRole.USER)

                    val safe = reportNos(get("/api/processed-foods?size=50&verdict=SAFE", token))
                    safe.contains("199104611019") shouldBe true
                    safe.contains("2001054954151") shouldBe false
                    reportNos(get("/api/processed-foods?size=50", token)).contains("2001054954151") shouldBe true
                }
            }
            `when`("묶음의 한 상품 상세를 보면") {
                then("같은 묶음 형제 목록(id·용량)이 실린다 — 자기 자신 포함, id 순") {
                    val big = seed("2001054954151", "바나나킥", "농심", "140g", "0.2000")
                    val small = seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    val siblings = get("/api/processed-foods/$small").path("sameNameProducts")

                    siblings.map { it.path("id").asLong() to it.path("capacity").asText() } shouldBe listOf(big to "140g", small to "75g").sortedBy { it.first }
                }
            }
        }

        given("묶음 대표 상품에 사진이 없을 때 — S-954 ①") {
            `when`("같은 묶음 형제에 원본 사진만 있으면") {
                then("목록·검색 카드와 대표 상세가 형제 사진을 쓴다(DB 는 그대로)") {
                    seed("IMG-1", "폴백라면", "농심")
                    seed("IMG-2", "폴백라면컵", "농심")
                    val item = get("/api/processed-foods?query=폴백라면").path("items").single()
                    val representative = item.path("id").asLong()
                    val sibling = listOf("IMG-1", "IMG-2").first { it != item.path("reportNo").asText() }
                    execute("UPDATE processed_food_product SET image_url = 'http://www.haccp.or.kr/fresh/prdimg/sibling.jpg' WHERE report_no = '$sibling'")

                    get("/api/processed-foods?query=폴백라면").path("items").single().path("imageUrl").asText() shouldBe "https://www.haccp.or.kr/fresh/prdimg/sibling.jpg"
                    get("/api/processed-foods/$representative").let { detail ->
                        detail.path("image").path("url").asText() shouldBe "https://www.haccp.or.kr/fresh/prdimg/sibling.jpg"
                        detail.path("images").map { it.path("url").asText() } shouldBe listOf("https://www.haccp.or.kr/fresh/prdimg/sibling.jpg")
                    }
                    dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT image_url FROM processed_food_product WHERE id = $representative").use { r -> r.next(); r.getString(1) } } } shouldBe null
                }
            }
            `when`("형제 중 하나는 원본, 하나는 승인된 누끼면") {
                then("승인된 누끼를 먼저 쓰고, 검수 대기 누끼는 쓰지 않는다") {
                    val plain = seed("IMG-1", "폴백과자", "오리온")
                    seed("IMG-2", "폴백과자 120g", "오리온")
                    seed("IMG-3", "폴백과자 멀티팩", "오리온")
                    val others = listOf("IMG-2", "IMG-3")
                    execute("UPDATE processed_food_product SET image_url = 'https://img.test/original.jpg' WHERE report_no = '${others[0]}'")
                    execute("UPDATE processed_food_product SET cutout_image_url = 'https://cdn.test/pending.png', cutout_review_status = 'PENDING' WHERE report_no = '${others[0]}'")
                    execute("UPDATE processed_food_product SET image_url = 'https://img.test/other.jpg', cutout_image_url = 'https://cdn.test/approved.png', cutout_review_status = 'APPROVED' WHERE report_no = '${others[1]}'")

                    get("/api/processed-foods/$plain").path("image").path("url").asText() shouldBe "https://cdn.test/approved.png"
                }
            }
            `when`("사진 있는 형제가 단종(마지막 미러에서 안 보인 지 3일 넘음)뿐이면 — Codex P2(#557 2차)") {
                then("카드·상세 모두 폴백하지 않는다(상세와 같은 가용성 기준)") {
                    execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 995401")
                    execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 995401")
                    execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (995401, 0, 'processedFoodMirrorJob', 's954-995401')")
                    execute("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (995401, 0, 995401, NOW(6), NOW(6), NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
                    try {
                        val alive = seed("IMG-1", "단종폴백라면", "농심")
                        seed("IMG-2", "단종폴백라면컵", "농심")
                        execute("UPDATE processed_food_product SET image_url = 'https://img.test/discontinued.jpg', last_seen_at = NOW(6) - INTERVAL 10 DAY WHERE report_no = 'IMG-2'")

                        get("/api/processed-foods?query=단종폴백라면").path("items").single().path("imageUrl").isNull shouldBe true
                        get("/api/processed-foods/$alive").path("image").isNull shouldBe true
                    } finally {
                        execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 995401")
                        execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 995401")
                    }
                }
            }
            `when`("대표 상품 자기 사진이 있으면") {
                then("형제 사진으로 바꾸지 않는다") {
                    val own = seed("IMG-1", "자기사진라면", "농심")
                    seed("IMG-2", "자기사진라면컵", "농심")
                    execute("UPDATE processed_food_product SET image_url = 'https://img.test/own.jpg' WHERE id = $own")
                    execute("UPDATE processed_food_product SET cutout_image_url = 'https://cdn.test/sibling.png', cutout_review_status = 'APPROVED' WHERE report_no = 'IMG-2'")

                    get("/api/processed-foods/$own").path("image").path("url").asText() shouldBe "https://img.test/own.jpg"
                }
            }
        }

        given("제조사 모름 행과 브랜드 접두 — Codex P2(#557)") {
            `when`("같은 이름의 한 행은 제조사(해태가루비)가 있고 한 행은 제조사 모름이면") {
                then("검색에서 한 묶음이다(브랜드 접두를 뗀 뒤에도 수정 전처럼 접힌다)") {
                    seed("FOLD-1", "해태 허니버터칩", "해태가루비")
                    seed("FOLD-2", "해태허니버터칩 60g", "알수없음")

                    reportNos(get("/api/processed-foods?query=허니버터칩")).count { it in setOf("FOLD-1", "FOLD-2") } shouldBe 1
                }
            }
            `when`("제조사 모름 행 이름에만 관측 브랜드(오뚜기)가 붙어 있으면") {
                then("브랜드를 뗀 이름으로 제조사 있는 행에 접힌다") {
                    seed("FOLD-3", "진라면 순한맛", "오뚜기라면주식회사")
                    seed("FOLD-4", "오뚜기 진라면 순한맛 120g", "알수없음")

                    reportNos(get("/api/processed-foods?query=진라면")).count { it in setOf("FOLD-3", "FOLD-4") } shouldBe 1
                }
            }
        }
    }
}
