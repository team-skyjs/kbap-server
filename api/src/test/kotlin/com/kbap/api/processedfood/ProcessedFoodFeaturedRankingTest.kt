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
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodFeaturedRankingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        val binggrae = "㈜빙그레,F1:경기도 남양주시 도농동 344-3,F2:경상남도 김해시 한림면 병동리 1078-1"

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun seed(reportNo: String, name: String, manufacturer: String, seenHoursAgo: Int = 0) {
            fun q(v: String) = "'" + v.replace("'", "''") + "'"
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '가공유', ${q(manufacturer)}, 'HACCP', REPEAT('a', 64), NOW(6), " +
                    "NOW(6) - INTERVAL $seenHoursAgo HOUR, 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        fun seedBananaShelf() {
            seed("BM-1", "빙그레 바나나맛 우유", binggrae, seenHoursAgo = 30)
            seed("BM-2", "빙그레 바나나맛 우유", binggrae, seenHoursAgo = 2)
            seed("BM-3", "빙그레 바나나맛 우유", "㈜빙그레,경상남도 김해시 한림면 병동리 1078-1", seenHoursAgo = 10)
            listOf("까삭 바나나맛", "제티 바나나맛", "컵케익바나나맛", "타요쌀눈 바나나맛", "현미스낵바나나맛", "초코파이 정 바나나맛").forEachIndexed { i, name ->
                seed("BX-$i", name, "㈜오리온 제${i}공장")
            }
        }
        fun member(id: Long): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            execute("UPDATE member SET scan_count = 0, scan_unlocked = 1 WHERE id = $id")
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun scan(token: String, text: String): JsonNode = mapper.readTree(
            mockMvc.post("/api/processed-foods/scan/name") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("ocrText" to text))
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun search(query: String): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods") {
                header("X-API-Version", "3.2")
                param("query", query)
                param("size", "30")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun boostedOf(analysisId: String): List<String> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT JSON_EXTRACT(request_document, '$.boostedReportNos') FROM scan_operation WHERE request_key = UUID_TO_BIN('$analysisId')").use { r ->
                    r.next(); r.getString(1)?.let { mapper.readValue(it, List::class.java).map(Any?::toString) } ?: emptyList()
                }
            }
        }
        fun reportNos(node: JsonNode): List<String> = node.map { it.path("reportNo").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec { execute("DELETE FROM processed_food_product") }

        given("가공식품 이름 스캔 대표 상품 우선(KB-858)") {
            `when`("흘림 로고가 깨지고 '바나나맛'만 읽힌 OCR 을 스캔하면(10/22 실기)") {
                then("빙그레바나나맛우유가 1위이고, 공장별 3행은 가장 최근에 본 1칸으로 묶이며, 올린 품목이 기록에 남는다") {
                    seedBananaShelf()
                    val p = scan(member(85801L), "SrcafA\n바나나맛")
                    val candidates = p.path("candidates")
                    candidates[0].path("reportNo").asText() shouldBe "BM-2"
                    reportNos(candidates).count { it.startsWith("BM-") } shouldBe 1
                    candidates.size() shouldBe 7
                    boostedOf(p.path("analysisId").asText()) shouldBe listOf("BM-2")
                }
            }
            `when`("OCR 에 다른 제조사(매일)가 보이면") {
                then("대표 상품을 올리지 않고 기존 순위대로다") {
                    seedBananaShelf()
                    seed("MI-1", "매일 바나나맛 우유", "매일유업㈜ 광주공장")
                    val p = scan(member(85802L), "매일 바나나맛 우유")
                    p.path("candidates")[0].path("reportNo").asText() shouldBe "MI-1"
                    boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                }
            }
            `when`("대표 상품이 생산종료로 판정되면") {
                then("올리지 않는다") {
                    seed("BM-OLD", "빙그레 바나나맛 우유", binggrae, seenHoursAgo = 24 * 10)
                    seed("BX-A", "까삭 바나나맛", "㈜오리온")
                    execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990301")
                    execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990301")
                    execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (990301, 0, 'processedFoodMirrorJob', 'fr-990301')")
                    execute("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (990301, 0, 990301, NOW(6), NOW(6), NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
                    try {
                        val p = scan(member(85803L), "SrcafA\n바나나맛")
                        reportNos(p.path("candidates")) shouldBe listOf("BX-A")
                        boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                    } finally {
                        execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990301")
                        execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990301")
                    }
                }
            }
            `when`("별칭이 걸리지 않는 OCR 이면") {
                then("기존 순위 그대로다(정확 → 앞 일치 → 포함, 같은 등급은 짧은 이름 → id)") {
                    seed("SR-1", "왕감자칩", "㈜태광")
                    seed("SR-2", "감자스틱매운맛", "㈜태광")
                    seed("SR-3", "감자스틱", "㈜태광")
                    seed("SR-4", "왕감자스틱", "㈜태광")
                    val p = scan(member(85804L), "감자스틱")
                    reportNos(p.path("candidates")) shouldBe listOf("SR-3", "SR-2", "SR-4")
                    boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                }
            }
            `when`("대표 상품이 아닌 제품도 공장별 여러 행이면") {
                then("같은 이름·같은 제조사는 가장 최근에 본 1칸, 제조사가 다르면 따로") {
                    seed("DUP-1", "감자스틱", "㈜태광_파주공장", seenHoursAgo = 20)
                    seed("DUP-2", "감자스틱", "㈜태광_김해공장", seenHoursAgo = 1)
                    seed("DUP-3", "감자스틱", "㈜다른제과")
                    reportNos(scan(member(85806L), "감자스틱").path("candidates")) shouldBe listOf("DUP-2", "DUP-3")
                }
            }
            `when`("DB 제조사가 '알수없음'인 대표 상품 행이면") {
                then("이름으로만 맞춰 올린다") {
                    seed("SH-1", "신라면", "알수없음")
                    seed("SH-2", "신라면볶음면", "㈜농심")
                    val p = scan(member(85807L), "농심\n辛라면")
                    p.path("candidates")[0].path("reportNo").asText() shouldBe "SH-1"
                    boostedOf(p.path("analysisId").asText()) shouldBe listOf("SH-1")
                }
            }
            `when`("대표 상품 제조사 이름 안에 다른 제조사 이름이 들어 있으면(롯데칠성 ⊃ 롯데)") {
                then("자기 제조사로 보고 올린다") {
                    seed("CS-1", "칠성사이다", "롯데칠성음료㈜")
                    seed("CS-2", "칠성사이다제로", "롯데칠성음료㈜")
                    val p = scan(member(85808L), "롯데칠성\n칠성사이다 제로")
                    boostedOf(p.path("analysisId").asText()) shouldBe listOf("CS-1")
                }
            }
            `when`("짧은 별칭의 대표 상품과 더 구체적인 대표 상품이 함께 걸리면 — 공부 막음") {
                then("더 길게 일치한 쪽만 올리고, 짧은 쪽은 올리지 않는다") {
                    seed("FK-1", "포카칩", "㈜오리온")
                    seed("FK-2", "포카리스웨트", "동아오츠카㈜")
                    seed("FK-3", "불닭볶음면", "삼양식품㈜")
                    seed("FK-4", "까르보불닭볶음면", "삼양식품㈜")
                    scan(member(85811L), "포카리스웨트").let { p ->
                        p.path("candidates")[0].path("reportNo").asText() shouldBe "FK-2"
                        boostedOf(p.path("analysisId").asText()) shouldBe listOf("FK-2")
                    }
                    scan(member(85812L), "까르보불닭볶음면").let { p ->
                        p.path("candidates")[0].path("reportNo").asText() shouldBe "FK-4"
                        boostedOf(p.path("analysisId").asText()) shouldBe listOf("FK-4")
                    }
                }
            }
            `when`("이름이 '…맛'으로 끝나는 대표 상품을 띄어 쓴 OCR 로 스캔하면 — 공부 2R 재발") {
                then("그 맛의 대표 상품이 1위다") {
                    seed("JR-1", "진라면 매운맛", "㈜오뚜기")
                    seed("JR-2", "진라면 순한맛", "㈜오뚜기")
                    scan(member(85831L), "진라면 순한맛").path("candidates")[0].path("reportNo").asText() shouldBe "JR-2"
                    scan(member(85832L), "진라면 매운맛").path("candidates")[0].path("reportNo").asText() shouldBe "JR-1"
                }
            }
            `when`("같은 제품이 '제조사+이름' 행으로도 있으면 — 공부 2R 요청 1") {
                then("대표 상품 행의 DB 제조사가 '알수없음'이어도 한 칸으로 묶인다(fixture F05 형태)") {
                    seed("JR-1", "진라면 매운맛", "알수없음", seenHoursAgo = 1)
                    seed("JR-3", "오뚜기 진라면 매운맛", "㈜오뚜기", seenHoursAgo = 5)
                    val candidates = reportNos(scan(member(85833L), "오뚜기\n진라면(매운맛)").path("candidates"))
                    candidates.first() shouldBe "JR-1"
                    candidates.count { it == "JR-1" || it == "JR-3" } shouldBe 1
                }
            }
            `when`("OCR 이 '바나나맛'/'우유'로 줄을 나눠 이름이 '우유'뿐인 행이 정확 일치하면 — 공부 2R 요청 2") {
                then("대표 상품의 가장 긴 일치보다 짧은 정확 일치는 대표 상품 아래다") {
                    seedBananaShelf()
                    seed("MK-1", "우유", "서울우유협동조합")
                    seed("MK-2", "우유", "㈜푸르밀")
                    scan(member(85834L), "SrcafA\n바나나맛\n우유").path("candidates")[0].path("reportNo").asText() shouldBe "BM-2"
                }
            }
            `when`("두 글자 별칭이 다른 단어 속에만 있으면(불닭소스) — 공부") {
                then("올리지 않는다") {
                    seed("FK-3", "불닭볶음면", "삼양식품㈜")
                    seed("SA-1", "불닭소스", "삼양식품㈜")
                    val p = scan(member(85813L), "불닭소스")
                    p.path("candidates")[0].path("reportNo").asText() shouldBe "SA-1"
                    boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                }
            }
            `when`("카탈로그 밖 제품이 이름 그대로(정확 일치) 읽히면 — 공부") {
                then("그 제품이 먼저이고 대표 상품은 그 아래다") {
                    seed("FS-1", "빙그레 딸기맛 우유", "㈜빙그레")
                    seed("FS-2", "맥심 모카골드 마일드 커피믹스", "동서식품㈜")
                    seed("FS-3", "올리브 짜파게티", "㈜농심")
                    seed("FS-4", "비락식혜", "팔도㈜")
                    seed("PB-1", "딸기맛 포키", "㈜해태")
                    seed("PB-2", "노브랜드 딸기맛 우유", "㈜이마트")
                    seed("PB-3", "맥심 화이트골드", "동서식품㈜")
                    seed("PB-4", "짜파게티 범벅", "㈜농심")
                    seed("PB-5", "식혜", "㈜웰그린")
                    listOf(
                        "딸기맛 포키" to listOf("PB-1", "FS-1"),
                        "노브랜드 딸기맛 우유" to listOf("PB-2", "FS-1"),
                        "맥심 화이트골드" to listOf("PB-3", "FS-2"),
                        "짜파게티 범벅" to listOf("PB-4", "FS-3"),
                        "식혜" to listOf("PB-5", "FS-4"),
                    ).forEachIndexed { i, (ocr, top2) ->
                        reportNos(scan(member(85820L + i), ocr).path("candidates")).take(2) shouldBe top2
                    }
                }
            }
            `when`("맞는 제품이 30개를 넘으면") {
                then("후보를 30개까지 한 번에 준다") {
                    (1..40).forEach { seed("MANY-$it", "새우스낵$it", "㈜농심") }
                    scan(member(85805L), "새우스낵").path("candidates").size() shouldBe 30
                }
            }
        }

        given("가공식품 탭 검색 대표 상품 우선(KB-858)") {
            `when`("'바나나맛'으로 검색하면") {
                then("빙그레바나나맛우유가 1위, 공장별 3행은 1칸") {
                    seedBananaShelf()
                    val items = search("바나나맛").path("items")
                    items[0].path("reportNo").asText() shouldBe "BM-2"
                    reportNos(items).count { it.startsWith("BM-") } shouldBe 1
                }
            }
            `when`("별칭이 걸리지 않는 검색어면") {
                then("기존 순위 그대로다") {
                    seed("SR-1", "왕감자칩", "㈜태광")
                    seed("SR-2", "감자스틱매운맛", "㈜태광")
                    seed("SR-3", "감자스틱", "㈜태광")
                    reportNos(search("감자스틱").path("items")) shouldBe listOf("SR-3", "SR-2")
                }
            }
        }
    }
}
