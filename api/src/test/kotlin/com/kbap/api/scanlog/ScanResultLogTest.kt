package com.kbap.api.scanlog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.BackgroundTasks
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.api.processedfood.FakeProcessedFoodPhotoReader
import com.kbap.api.scan.FakeMenuBoardVisionExtractor
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import com.kbap.common.port.llm.ProcessedFoodPhotoReason
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ScanResultLogTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var menuVision: FakeMenuBoardVisionExtractor
    @Autowired private lateinit var photoReader: FakeProcessedFoodPhotoReader

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private lateinit var taskExecutor: ThreadPoolTaskExecutor

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun token(memberId: Long, role: MemberRole = MemberRole.USER): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, memberId)
            return tokenIssuer.issueAccessToken(memberId, role)
        }
        fun upload(memberId: Long, segment: String): Pair<Long, String> {
            val path = "dev/images/$segment/2026/11/${memberId}_${UUID.randomUUID()}.jpg"
            execute("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES ($memberId, '$path', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))")
            val id = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM uploaded_image WHERE object_path = '$path'").use { rs -> rs.next(); rs.getLong(1) } } }
            return id to path
        }
        fun seedReadyFood(koreanName: String) = execute(
            "INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                "VALUES ('$koreanName', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
        )
        fun foodIdOf(koreanName: String): Long =
            dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM food WHERE korean_name = '$koreanName'").use { rs -> rs.next(); rs.getLong(1) } } }
        fun ticket(token: String): String = mapper.readTree(
            mockMvc.post("/api/scans/tickets") { header("Authorization", "Bearer $token") }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("ticket").asText()
        fun menuV2(token: String, path: String, expected: Int = 200) = mockMvc.post("/api/scans") {
            param("lang", "ko")
            param("currency", "USD")
            header("Authorization", "Bearer $token")
            header("X-API-Version", "2.0")
            header("X-App-Version", "1.0.7")
            header("X-Scan-Ticket", ticket(token))
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("imagePath" to path))
        }.andExpect { status { isEqualTo(expected) } }
        fun post(url: String, body: Map<String, Any?>, token: String, expected: Int = 200) = mockMvc.post(url) {
            header("X-API-Version", "3.2")
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }.andExpect { status { isEqualTo(expected) } }
        fun vision(auth: String, path: String, expected: Int = 200) =
            post("/api/processed-foods/scan/vision", mapOf("imagePath" to path, "clientRequestId" to UUID.randomUUID().toString(), "lang" to "ko"), auth, expected)
        fun logsOf(memberId: Long): List<Map<String, Any?>> {
            BackgroundTasks.drain(taskExecutor)
            return JdbcTemplate(dataSource).queryForList("SELECT * FROM scan_result_log WHERE member_id = ? ORDER BY id", memberId)
        }
        fun summaryOf(row: Map<String, Any?>): JsonNode = mapper.readTree(row["response_summary"] as String)
        fun admin(url: String): JsonNode = mapper.readTree(
            mockMvc.get(url) {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )

        beforeSpec {
            TestTables.clearAll(dataSource)
            photoReader.reset()
            execute("DELETE FROM processed_food_product")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('19700001001', '새우깡', '새우깡', '과자', '농심', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            photoReader.reset()
        }

        given("메뉴판 스캔 v2") {
            `when`("메뉴 하나는 DB 에 있고 하나는 없으면") {
                then("사진·헤더와 함께 MENU_LIST 성공 기록이 남고 없는 메뉴는 foodId 가 null 이다") {
                    val memberId = 98301L
                    val auth = token(memberId)
                    val (imageId, path) = upload(memberId, "menu-scans")
                    seedReadyFood("기록김치찌개")
                    menuVision.program(path, listOf(ExtractedMenu("기록김치찌개", "기록김치찌개", 9000, null), ExtractedMenu("없는메뉴", "없는메뉴", null, null)))

                    menuV2(auth, path)

                    val row = logsOf(memberId).single()
                    row["scan_mode"] shouldBe "MENU_V2"
                    row["outcome"] shouldBe "SUCCESS"
                    row["ui_kind"] shouldBe "MENU_LIST"
                    row["error_code"] shouldBe null
                    (row["uploaded_image_id"] as Number).toLong() shouldBe imageId
                    row["app_version"] shouldBe "1.0.7"
                    row["api_version"] shouldBe "2.0"
                    val summary = summaryOf(row)
                    summary.path("degraded").asBoolean() shouldBe false
                    summary.path("items").size() shouldBe 0
                    summary.path("photoOnlyCount").asInt() shouldBe 2
                    val byName = summary.path("photoOnly").associateBy { it.path("name").asText() }
                    byName.getValue("기록김치찌개").path("foodId").asLong() shouldBe foodIdOf("기록김치찌개")
                    byName.getValue("없는메뉴").path("foodId").isNull shouldBe true
                }
            }
            `when`("메뉴판을 인식하지 못하면") {
                then("SCAN-003 RETAKE 기록이 남는다") {
                    val memberId = 98302L
                    val auth = token(memberId)
                    val (_, path) = upload(memberId, "menu-scans")
                    menuVision.program(path, emptyList())

                    menuV2(auth, path, expected = 400)

                    val row = logsOf(memberId).single()
                    row["outcome"] shouldBe "RETAKE"
                    row["ui_kind"] shouldBe "RETAKE"
                    row["error_code"] shouldBe "SCAN-003"
                }
            }
            `when`("비전 서버가 실패하면") {
                then("ERROR 기록에 에러 코드가 남는다") {
                    val memberId = 98303L
                    val auth = token(memberId)
                    val (_, path) = upload(memberId, "menu-scans")
                    menuVision.unavailableOn(path)

                    menuV2(auth, path, expected = 503)

                    val row = logsOf(memberId).single()
                    row["outcome"] shouldBe "ERROR"
                    row["ui_kind"] shouldBe "ERROR"
                    row["error_code"] shouldBe "SCAN-006"
                }
            }
        }

        given("가공식품 이름·성분표 스캔") {
            `when`("이름 스캔이 후보를 찾으면") {
                then("후보 id 가 담긴 PFOOD_CANDIDATES 기록이 남는다") {
                    val memberId = 98311L
                    val auth = token(memberId)
                    val (imageId, path) = upload(memberId, "packaged-scans")

                    post("/api/processed-foods/scan/name", mapOf("ocrText" to "농심 새우깡", "lang" to "ko", "imagePath" to path), auth)

                    val row = logsOf(memberId).single()
                    row["scan_mode"] shouldBe "PFOOD_NAME"
                    row["outcome"] shouldBe "SUCCESS"
                    row["ui_kind"] shouldBe "PFOOD_CANDIDATES"
                    (row["uploaded_image_id"] as Number).toLong() shouldBe imageId
                    summaryOf(row).path("candidates").first().path("reportNo").asText() shouldBe "19700001001"
                }
            }
            `when`("성분표를 읽으면") {
                then("사진 없이 LABEL_RESULT 기록이 남는다") {
                    val memberId = 98312L
                    val auth = token(memberId)

                    post(
                        "/api/processed-foods/scan/label",
                        mapOf("clientRequestId" to UUID.randomUUID().toString(), "pages" to listOf(mapOf("ocrText" to "원재료명: 소맥분(밀), 새우\n알레르기 유발물질: 밀, 새우 함유")), "lang" to "ko"),
                        auth,
                    )

                    val row = logsOf(memberId).single()
                    row["scan_mode"] shouldBe "PFOOD_LABEL"
                    row["outcome"] shouldBe "SUCCESS"
                    row["ui_kind"] shouldBe "LABEL_RESULT"
                    row["uploaded_image_id"] shouldBe null
                    summaryOf(row).path("state").asText() shouldBe "LABEL_RESULT"
                }
            }
        }

        given("가공식품 사진 스캔(scan/vision)") {
            `when`("앞면이면") {
                then("visionKind 와 후보가 남는다") {
                    val memberId = 98321L
                    val auth = token(memberId)
                    val (_, path) = upload(memberId, "packaged-scans")
                    photoReader.program(path, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.PACKAGE_FRONT, 0.95, productName = "새우깡", maker = "농심"))

                    vision(auth, path)

                    val row = logsOf(memberId).single()
                    row["scan_mode"] shouldBe "PFOOD_VISION"
                    row["vision_kind"] shouldBe "PACKAGE_FRONT"
                    row["outcome"] shouldBe "SUCCESS"
                    row["ui_kind"] shouldBe "PFOOD_CANDIDATES"
                }
            }
            `when`("메뉴판 사진이면") {
                then("NOT_PACKAGED 로 남는다") {
                    val memberId = 98322L
                    val auth = token(memberId)
                    val (_, path) = upload(memberId, "packaged-scans")
                    photoReader.program(path, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.MENU, 0.9))

                    vision(auth, path)

                    val row = logsOf(memberId).single()
                    row["outcome"] shouldBe "SUCCESS"
                    row["ui_kind"] shouldBe "NOT_PACKAGED"
                    row["vision_kind"] shouldBe "MENU"
                }
            }
            `when`("알아볼 수 없는 사진이면") {
                then("RETAKE 로 남고 사유가 요약에 들어간다") {
                    val memberId = 98323L
                    val auth = token(memberId)
                    val (_, path) = upload(memberId, "packaged-scans")
                    photoReader.program(path, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.OTHER, 0.8, reason = ProcessedFoodPhotoReason.BLURRY))

                    vision(auth, path)

                    val row = logsOf(memberId).single()
                    row["outcome"] shouldBe "RETAKE"
                    row["ui_kind"] shouldBe "RETAKE"
                    summaryOf(row).path("reason").asText() shouldBe "BLURRY"
                }
            }
        }

        given("어드민 스캔 사진 조회") {
            `when`("기록이 있는 사진과 없는 사진을 보면") {
                then("피드에 최신 기록 요약이, 상세에 기록 전체가 나오고 없는 사진은 null 이다") {
                    val memberId = 98331L
                    val auth = token(memberId)
                    val (imageId, path) = upload(memberId, "packaged-scans")
                    val (bareId, _) = upload(memberId, "packaged-scans")
                    photoReader.program(path, ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.MENU, 0.9))
                    vision(auth, path)
                    menuVision.program(path, listOf(ExtractedMenu("미등록메뉴", "미등록메뉴", null, null)))
                    menuV2(auth, path)
                    logsOf(memberId).size shouldBe 2

                    val items = admin("/api/admin/scan-photos?memberId=$memberId").path("payload").path("items")
                    val logged = items.single { it.path("uploadedImageId").asLong() == imageId }
                    logged.path("scanOutcome").asText() shouldBe "SUCCESS"
                    logged.path("uiKind").asText() shouldBe "MENU_LIST"
                    items.single { it.path("uploadedImageId").asLong() == bareId }.path("scanOutcome").isNull shouldBe true

                    val detail = admin("/api/admin/scan-photos/$imageId/scan-results").path("payload")
                    detail.path("records").map { it.path("scanMode").asText() } shouldBe listOf("MENU_V2", "PFOOD_VISION")
                    detail.path("records").first().path("responseSummary").path("photoOnly").first().path("foodId").isNull shouldBe true
                    admin("/api/admin/scan-photos/$bareId/scan-results").path("payload").isNull shouldBe true
                }
            }
        }

        given("기록이 실패하는 상황") {
            val failingWriter = object : ScanResultLogWriter(JdbcTemplate()) {
                override fun write(row: ScanResultLogRow) {
                    throw IllegalStateException("db down")
                }
            }
            val recorder = ScanResultRecorder(failingWriter)

            `when`("스캔이 성공했는데 기록 쓰기가 예외를 던지면") {
                then("스캔 결과가 그대로 돌아온다") {
                    shouldNotThrowAny {
                        recorder.record(1L, ScanMode.MENU_V2, "p", { _: String -> ScanResultSummaries.empty() }) { "응답" } shouldBe "응답"
                    }
                }
            }
            `when`("요약 만들기가 예외를 던지면") {
                then("스캔 결과가 그대로 돌아온다") {
                    recorder.record(1L, ScanMode.MENU_V2, "p", { _: String -> error("요약 실패") }) { "응답" } shouldBe "응답"
                }
            }
            `when`("스캔이 실패했는데 기록 쓰기도 예외를 던지면") {
                then("원래 스캔 예외가 그대로 나간다") {
                    shouldThrow<BusinessException> {
                        recorder.record(1L, ScanMode.MENU_V2, "p", { _: String -> ScanResultSummaries.empty() }) {
                            throw BusinessException(ErrorCode.MENU_BOARD_NOT_DETECTED)
                        }
                    }.errorCode shouldBe ErrorCode.MENU_BOARD_NOT_DETECTED
                }
            }
        }

        given("요약 JSON 크기 상한") {
            `when`("메뉴가 아주 많고 이름이 길면") {
                then("직렬화 결과가 상한을 넘지 않는다") {
                    val huge = (1..2000).map { mapOf("name" to "가".repeat(500), "foodId" to it, "riskLevel" to "SAFE") }
                    ScanResultSummaries.toJson(mapOf("photoOnly" to huge, "photoOnlyCount" to huge.size)).length shouldBeLessThanOrEqual ScanResultSummaries.MAX_SUMMARY_CHARS
                }
            }
        }
    }
}
