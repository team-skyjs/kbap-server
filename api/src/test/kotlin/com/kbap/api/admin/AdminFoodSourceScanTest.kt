package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.BackgroundTasks
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.api.scan.FakeMenuBoardVisionExtractor
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ExtractedMenu
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class AdminFoodSourceScanTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var menuVision: FakeMenuBoardVisionExtractor

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private lateinit var taskExecutor: ThreadPoolTaskExecutor

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun token(memberId: Long): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }
        fun upload(memberId: Long): Pair<Long, String> {
            val path = "dev/images/scans/2026/11/${memberId}_${UUID.randomUUID()}.jpg"
            execute("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES ($memberId, '$path', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))")
            return longOf("SELECT id FROM uploaded_image WHERE object_path = '$path'") to path
        }
        fun seedReadyFood(koreanName: String) = execute(
            "INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                "VALUES ('$koreanName', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
        )
        fun foodIdOf(koreanName: String) = longOf("SELECT id FROM food WHERE korean_name = '$koreanName'")
        fun scan(token: String, path: String) {
            val ticket = mapper.readTree(
                mockMvc.post("/api/scans/tickets") { header("Authorization", "Bearer $token") }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload").path("ticket").asText()
            mockMvc.post("/api/scans") {
                param("lang", "ko"); param("currency", "USD")
                header("Authorization", "Bearer $token")
                header("X-API-Version", "2.0")
                header("X-Scan-Ticket", ticket)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("imagePath" to path))
            }.andExpect { status { isOk() } }
            BackgroundTasks.drain(taskExecutor)
        }
        fun adminToken() = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)
        fun sources(foodId: Long): JsonNode = mapper.readTree(
            mockMvc.get("/api/admin/foods/$foodId/source-scans") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer ${adminToken()}")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun backfill(target: String = "dev"): Pair<Int, JsonNode> = mockMvc.post("/api/admin/scan-result-foods/backfill") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${adminToken()}")
            header("X-Target-Environment", target)
        }.andReturn().response.let { it.status to mapper.readTree(it.getContentAsString(Charsets.UTF_8)) }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("검수 화면의 출처 스캔 사진 — S-967") {
            `when`("메뉴판 스캔에서 미등록 메뉴(미완성 음식 생성)와 매칭 메뉴가 나오면") {
                then("두 음식 모두 그 스캔 사진·종류·원문 이름·회원·업로드 이미지 id 로 이어진다(RECORDED)") {
                    seedReadyFood("출처매칭국")
                    val auth = token(97301)
                    val (uploadedId, path) = upload(97301)
                    menuVision.program(path, listOf(ExtractedMenu("Monster", "출처몬스터피자", 15000, null), ExtractedMenu("Soup", "출처매칭국", 9000, null)))

                    scan(auth, path)

                    val created = foodIdOf("출처몬스터피자")
                    sources(created).path("items").single().let {
                        it.path("memberId").asLong() shouldBe 97301
                        it.path("uploadedImageId").asLong() shouldBe uploadedId
                        it.path("imageUrl").asText().endsWith(path) shouldBe true
                        it.path("scanMode").asText() shouldBe "MENU_V2"
                        it.path("scannedName").asText() shouldBe "출처몬스터피자"
                        it.path("matched").asBoolean() shouldBe false
                        it.path("linkSource").asText() shouldBe "RECORDED"
                    }
                    sources(foodIdOf("출처매칭국")).path("items").single().path("matched").asBoolean() shouldBe true
                }
            }

            `when`("연결 행이 생기기 전 기록을 백필하면") {
                then("요약의 음식 id 는 RECORDED, 미등록 이름은 정규화 일치로 NAME_BACKFILL 로 연결되고 다시 돌려도 늘지 않는다") {
                    execute("DELETE FROM scan_result_log")
                    seedReadyFood("출처옛매칭")
                    execute("INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) VALUES ('출처옛미등록', '', 0, '{}', '{}', '[]', 'FAILED', 'ACTIVE', NOW(6), NOW(6))")
                    val matchedId = foodIdOf("출처옛매칭")
                    val summary = mapper.writeValueAsString(
                        mapOf(
                            "items" to listOf(mapOf("name" to "출처 옛 미등록", "matched" to false, "foodId" to null), mapOf("name" to "출처옛매칭", "matched" to true, "foodId" to matchedId)),
                            "photoOnly" to emptyList<Any>(),
                        ),
                    )
                    FoodTestSeed.seedMemberAvoiding(dataSource, 97302)
                    execute(
                        "INSERT INTO scan_result_log (member_id, image_path, scan_mode, outcome, ui_kind, response_summary, scanned_at, status, created_at, updated_at) " +
                            "VALUES (97302, 'dev/images/scans/old.jpg', 'MENU_V2', 'SUCCESS', 'MENU_LIST', '${summary.replace("'", "''")}', NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                    )

                    backfill().let { (status, body) ->
                        status shouldBe 200
                        body.path("payload").path("linked").asInt() shouldBe 2
                    }
                    backfill().second.path("payload").path("linked").asInt() shouldBe 0

                    sources(foodIdOf("출처옛미등록")).path("items").single().let {
                        it.path("linkSource").asText() shouldBe "NAME_BACKFILL"
                        it.path("scannedName").asText() shouldBe "출처 옛 미등록"
                    }
                    sources(matchedId).path("items").single().path("linkSource").asText() shouldBe "RECORDED"
                }
            }

            `when`("기록이 시작되기 전에 만들어진 음식이면") {
                then("items 는 비고 foodCreatedAt·sourceRecordedSince 로 '출처 기록 없음'을 판단할 수 있다") {
                    execute("INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) VALUES ('출처오래된', '', 0, '{}', '{}', '[]', 'FAILED', 'ACTIVE', '2026-10-01 00:00:00', NOW(6))")

                    sources(foodIdOf("출처오래된")).let {
                        it.path("items").size() shouldBe 0
                        it.path("foodCreatedAt").asText().startsWith("2026-10-01") shouldBe true
                    }
                }
            }

            `when`("백필 환경 헤더가 서버와 다르면") {
                then("409 FOOD-026") {
                    backfill(target = "prod").let { (status, body) ->
                        status shouldBe 409
                        body.path("code").asText() shouldBe "FOOD-026"
                    }
                }
            }
        }
    }
}
