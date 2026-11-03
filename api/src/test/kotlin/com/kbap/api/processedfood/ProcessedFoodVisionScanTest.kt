package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import com.kbap.common.port.llm.ProcessedFoodPhotoReason
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
class ProcessedFoodVisionScanTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var reader: FakeProcessedFoodPhotoReader

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(id: Long): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun photo(memberId: Long, sizeBytes: Long = 1024, segment: String = "packaged-scans"): String {
            val path = "dev/images/$segment/2026/11/${memberId}_${UUID.randomUUID()}.jpg"
            execute("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES ($memberId, '$path', 'image/jpeg', $sizeBytes, 'ACTIVE', NOW(6), NOW(6))")
            return path
        }
        fun post(path: String, body: Map<String, Any?>, token: String, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.post(path) {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun vision(token: String, imagePath: String, key: String = UUID.randomUUID().toString(), expected: Int = 200) =
            post("/api/processed-foods/scan/vision", mapOf("imagePath" to imagePath, "clientRequestId" to key, "lang" to "ko"), token, expected)
        fun front(name: String?, maker: String? = null, reportNo: String? = null) =
            ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.PACKAGE_FRONT, 0.95, productName = name, maker = maker, reportNo = reportNo)

        beforeSpec {
            TestTables.clearAll(dataSource)
            reader.reset()
            execute("DELETE FROM processed_food_product")
            listOf(
                Triple("19700001001", "새우깡", "농심"),
                Triple("19760344001", "바나나맛우유", "빙그레"),
                Triple("19800000009", "감자깡", "농심"),
            ).forEach { (reportNo, name, maker) ->
                execute(
                    "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                        "VALUES ('$reportNo', '$name', '$name', '과자', '$maker', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                )
            }
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            reader.reset()
        }

        given("가공식품 사진 스캔(scan/vision)") {
            `when`("앞면 사진에서 이름을 읽으면") {
                then("후보를 주고 같은 analysisId 로 기존 확정이 된다") {
                    val token = member(96901L)
                    val path = photo(96901L)
                    reader.program(path, front("새우깡", "농심"))
                    val payload = vision(token, path).path("payload")
                    payload.path("kind").asText() shouldBe "PACKAGE_FRONT"
                    payload.path("state").asText() shouldBe "CANDIDATES"
                    payload.path("charged").asBoolean() shouldBe false
                    payload.path("front").path("productName").asText() shouldBe "새우깡"
                    payload.path("front").path("candidates").first().path("reportNo").asText() shouldBe "19700001001"
                    val confirmed = post("/api/processed-foods/scan/confirm", mapOf("analysisId" to payload.path("analysisId").asText(), "reportNo" to "19700001001"), token).path("payload")
                    confirmed.path("state").asText() shouldBe "CONFIRMED"
                }
            }
            `when`("읽은 품목보고번호가 등록돼 있으면") {
                then("이름이 깨져 읽혀도 그 제품이 맨 위다") {
                    val token = member(96902L)
                    val path = photo(96902L)
                    reader.program(path, front("b나나", reportNo = "19760344001"))
                    val candidates = vision(token, path).path("payload").path("front").path("candidates")
                    candidates.first().path("reportNo").asText() shouldBe "19760344001"
                }
            }
            `when`("앞면에서 아무것도 못 찾으면") {
                then("NO_CANDIDATE 이고 같은 analysisId 로 성분표 이어가기가 된다") {
                    val token = member(96903L)
                    val path = photo(96903L)
                    reader.program(path, front("없는제품이름"))
                    val payload = vision(token, path).path("payload")
                    payload.path("state").asText() shouldBe "NO_CANDIDATE"
                    val label = post(
                        "/api/processed-foods/scan/label",
                        mapOf("analysisId" to payload.path("analysisId").asText(), "pages" to listOf(mapOf("ocrText" to "원재료명: 소맥분(밀), 새우\n알레르기 유발물질: 밀, 새우 함유")), "lang" to "ko"),
                        token,
                    ).path("payload")
                    label.path("state").asText() shouldBe "LABEL_RESULT"
                }
            }
            `when`("성분표 사진이면") {
                then("기존 성분표 판정과 같은 결과를 label 에 준다") {
                    val token = member(96904L)
                    val path = photo(96904L)
                    reader.program(path, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.INGREDIENT_LABEL, 0.9, labelText = "원재료명: 소맥분(밀), 새우 10%\n알레르기 유발물질: 밀, 새우 함유"))
                    val payload = vision(token, path).path("payload")
                    payload.path("kind").asText() shouldBe "INGREDIENT_LABEL"
                    payload.path("state").asText() shouldBe "LABEL_RESULT"
                    payload.path("label").path("labelSource").asText() shouldBe "DECLARED"
                    payload.path("label").path("detections").map { it.path("code").asText() }.toSet().containsAll(setOf("WHEAT", "SHRIMP")) shouldBe true
                }
            }
            `when`("성분표로 판별했지만 글자를 못 읽으면") {
                then("400 PFOOD-006") {
                    val token = member(96905L)
                    val path = photo(96905L)
                    reader.program(path, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.INGREDIENT_LABEL, 0.4))
                    vision(token, path, expected = 400).path("code").asText() shouldBe "PFOOD-006"
                }
            }
            `when`("메뉴판·기타 사진이면") {
                then("종류만 주고 state=NOT_PACKAGED, 기타는 reason 을 준다") {
                    val token = member(96906L)
                    val menu = photo(96906L)
                    reader.program(menu, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.MENU, 0.88))
                    val menuPayload = vision(token, menu).path("payload")
                    menuPayload.path("kind").asText() shouldBe "MENU"
                    menuPayload.path("state").asText() shouldBe "NOT_PACKAGED"
                    menuPayload.path("front").isNull shouldBe true
                    val other = photo(96906L)
                    reader.program(other, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.OTHER, 0.7, ProcessedFoodPhotoReason.BLURRY))
                    vision(token, other).path("payload").path("reason").asText() shouldBe "BLURRY"
                }
            }
            `when`("같은 clientRequestId 로 다시 보내면") {
                then("비전을 다시 부르지 않고 같은 결과, 다른 사진이면 409 SCAN-005") {
                    val token = member(96907L)
                    val path = photo(96907L)
                    reader.program(path, front("감자깡", "농심"))
                    val key = UUID.randomUUID().toString()
                    val before = reader.calls.get()
                    val first = vision(token, path, key).path("payload")
                    val second = vision(token, path, key).path("payload")
                    reader.calls.get() shouldBe before + 1
                    second.path("analysisId").asText() shouldBe first.path("analysisId").asText()
                    second.path("front").path("candidates").map { it.path("reportNo").asText() } shouldBe first.path("front").path("candidates").map { it.path("reportNo").asText() }
                    vision(token, photo(96907L), key, expected = 409).path("code").asText() shouldBe "SCAN-005"
                }
            }
            `when`("비전이 실패한 뒤 같은 키로 다시 보내면") {
                then("503 SCAN-006 뒤 재시도는 다시 읽고 상한은 한 번만 센다") {
                    val token = member(96908L)
                    val path = photo(96908L)
                    reader.failOn(path, MenuBoardVisionUnavailableException(RuntimeException("테스트 장애")))
                    val key = UUID.randomUUID().toString()
                    vision(token, path, key, expected = 503).path("code").asText() shouldBe "SCAN-006"
                    reader.program(path, front("새우깡"))
                    vision(token, path, key).path("payload").path("kind").asText() shouldBe "PACKAGE_FRONT"
                    long("SELECT COUNT(*) FROM scan_operation WHERE member_id = 96908 AND key_namespace = 'PFOOD_VISION'") shouldBe 1L
                }
            }
            `when`("사진이 남의 것·다른 목적·2MB 초과면") {
                then("SCAN-001·PFOOD-014 이고 비전을 부르지 않는다") {
                    val token = member(96909L)
                    member(96910L)
                    val before = reader.calls.get()
                    vision(token, photo(96910L), expected = 400).path("code").asText() shouldBe "SCAN-001"
                    vision(token, photo(96909L, segment = "scans"), expected = 400).path("code").asText() shouldBe "SCAN-001"
                    vision(token, photo(96909L, sizeBytes = 2 * 1024 * 1024 + 1), expected = 400).path("code").asText() shouldBe "PFOOD-014"
                    vision(token, photo(96909L), "not-a-uuid", expected = 400).path("code").asText() shouldBe "COMMON-002"
                    reader.calls.get() shouldBe before
                }
            }
            `when`("하루 상한을 넘으면") {
                then("429 PFOOD-013 이고 비전을 부르지 않는다") {
                    val token = member(96911L)
                    val limit = 30L
                    execute(
                        "INSERT INTO scan_operation (member_id, key_namespace, request_key, manifest_hash, mode, lifecycle, initial_language, fx_state, request_document, limits_version, expires_at, result_expires_at, key_retire_after, status, created_at, updated_at) " +
                            "SELECT 96911, 'PFOOD_VISION', UNHEX(REPLACE(UUID(), '-', '')), UNHEX(SHA2(n, 256)), 'PROCESSED_FOOD_VISION', 'READ', 'ko', 'NONE', JSON_OBJECT(), 'pfood-vision-1', NOW(6), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6) " +
                            "FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $limit) SELECT n FROM seq) s",
                    )
                    val before = reader.calls.get()
                    vision(token, photo(96911L), expected = 429).path("code").asText() shouldBe "PFOOD-013"
                    reader.calls.get() shouldBe before
                }
            }
            `when`("어드민 스캔 사진 피드를 보면") {
                then("사진 스캔이 판별한 종류가 visionKind 로 붙고, 거치지 않은 사진은 null 이다") {
                    val token = member(96912L)
                    val menu = photo(96912L)
                    reader.program(menu, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.MENU, 0.9))
                    vision(token, menu)
                    val untouched = photo(96912L)
                    val items = mapper.readTree(
                        mockMvc.get("/api/admin/scan-photos?memberId=96912") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("items").associateBy { it.path("imageUrl").asText().substringAfterLast('/') }
                    items.getValue(menu.substringAfterLast('/')).path("visionKind").asText() shouldBe "MENU"
                    items.getValue(untouched.substringAfterLast('/')).path("visionKind").isNull shouldBe true
                }
            }
        }
    }
}
