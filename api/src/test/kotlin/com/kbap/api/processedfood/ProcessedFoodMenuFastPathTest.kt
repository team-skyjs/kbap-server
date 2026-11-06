package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.MenuPhotoClassificationFailed
import com.kbap.common.port.llm.MenuPhotoVerdict
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodMenuFastPathTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var reader: FakeProcessedFoodPhotoReader
    @Autowired private lateinit var jev: FakeMenuPhotoClassifier

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun text(sql: String): String? = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } } }
        fun member(id: Long): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun photo(memberId: Long): String {
            val path = "dev/images/packaged-scans/2026/11/${memberId}_${UUID.randomUUID()}.jpg"
            execute("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES ($memberId, '$path', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))")
            return path
        }
        fun vision(token: String, imagePath: String, eligible: Boolean? = true, key: String = UUID.randomUUID().toString(), expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.post("/api/processed-foods/scan/vision") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("imagePath" to imagePath, "clientRequestId" to key, "lang" to "ko") + (eligible?.let { mapOf("menuFastPathEligible" to it) } ?: emptyMap()))
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun sourceOf(key: String): String? = text("SELECT JSON_UNQUOTE(JSON_EXTRACT(request_document, '$.visionSource')) FROM scan_operation WHERE key_namespace = 'PFOOD_VISION' AND request_key = UUID_TO_BIN('$key')")
        val readerFront = ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.PACKAGE_FRONT, 0.9, productName = "없는과자")

        beforeEach {
            reader.reset()
            jev.reset()
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            reader.reset()
            jev.reset()
        }

        given("가공식품 사진 스캔의 메뉴판 바로가기 — KB-1015") {
            `when`("실험 대상의 첫 사진을 Jev 가 MENU 확률 0.980 으로 고르면") {
                then("기존 판독 없이 MENU·NOT_PACKAGED·미차감으로 저장하고, 같은 키 재요청은 두 모델 모두 다시 부르지 않는다") {
                    val token = member(96971L)
                    val path = photo(96971L)
                    val key = UUID.randomUUID().toString()
                    jev.program(path) { MenuPhotoVerdict.Menu(0.980) }

                    val payload = vision(token, path, key = key).path("payload")

                    payload.path("kind").asText() shouldBe "MENU"
                    payload.path("state").asText() shouldBe "NOT_PACKAGED"
                    payload.path("charged").asBoolean() shouldBe false
                    payload.path("confidence").asDouble() shouldBe 0.98
                    reader.calls.get() shouldBe 0
                    jev.calls.get() shouldBe 1
                    sourceOf(key) shouldBe "MENU_FAST_PATH"

                    vision(token, path, key = key).path("payload").path("kind").asText() shouldBe "MENU"
                    reader.calls.get() shouldBe 0
                    jev.calls.get() shouldBe 1
                }
            }

            `when`("MENU 확률이 0.979 이거나 다른 종류를 고르면") {
                then("재시도 없이 기존 판독을 한 번 부르고 그 결과를 준다") {
                    val token = member(96972L)
                    listOf<() -> MenuPhotoVerdict>({ MenuPhotoVerdict.Menu(0.979) }, { MenuPhotoVerdict.NotMenu }).forEach { answer ->
                        reader.reset()
                        jev.reset()
                        val path = photo(96972L)
                        val key = UUID.randomUUID().toString()
                        jev.program(path, answer)
                        reader.program(path, readerFront)

                        vision(token, path, key = key).path("payload").path("kind").asText() shouldBe "PACKAGE_FRONT"

                        jev.calls.get() shouldBe 1
                        reader.calls.get() shouldBe 1
                        sourceOf(key) shouldBe "READER"
                    }
                }
            }

            `when`("Jev 가 400·429·5xx·응답 형식 오류로 실패하면") {
                then("재시도 없이 기존 판독을 한 번 부른다") {
                    val token = member(96973L)
                    listOf("client_error", "rate_limited", "server_error", "invalid_response").forEach { reason ->
                        reader.reset()
                        jev.reset()
                        val path = photo(96973L)
                        jev.program(path) { throw MenuPhotoClassificationFailed(reason) }
                        reader.program(path, readerFront)

                        vision(token, path).path("payload").path("kind").asText() shouldBe "PACKAGE_FRONT"

                        jev.calls.get() shouldBe 1
                        reader.calls.get() shouldBe 1
                    }
                }
            }

            `when`("Jev 가 예산(테스트 300ms)을 넘기면") {
                then("호출을 취소하고 기존 판독 결과를 준다") {
                    val token = member(96974L)
                    val path = photo(96974L)
                    jev.program(path) { Thread.sleep(5_000); MenuPhotoVerdict.Menu(1.0) }
                    reader.program(path, readerFront)

                    val started = System.nanoTime()
                    vision(token, path).path("payload").path("kind").asText() shouldBe "PACKAGE_FRONT"

                    (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000) shouldBe true
                    reader.calls.get() shouldBe 1
                    repeat(20) { if (jev.interrupted.get() == 0) Thread.sleep(50) }
                    jev.interrupted.get() shouldBe 1
                }
            }

            `when`("Jev 실패 뒤 기존 판독도 실패하면") {
                then("기존 오류(503 SCAN-006)를 주고 같은 키 재시도는 다시 읽는다") {
                    val token = member(96975L)
                    val path = photo(96975L)
                    val key = UUID.randomUUID().toString()
                    jev.program(path) { throw MenuPhotoClassificationFailed("server_error") }
                    reader.failOn(path, MenuBoardVisionUnavailableException(RuntimeException("테스트 장애")))

                    vision(token, path, key = key, expected = 503).path("code").asText() shouldBe "SCAN-006"

                    reader.program(path, readerFront)
                    vision(token, path, key = key).path("payload").path("kind").asText() shouldBe "PACKAGE_FRONT"
                    jev.calls.get() shouldBe 2
                    reader.calls.get() shouldBe 2
                }
            }

            `when`("신호가 없거나 false 이거나, 실험 대상 회원이 아니면") {
                then("Jev 를 부르지 않는다") {
                    val token = member(96976L)
                    listOf(null, false).forEach { eligible ->
                        val path = photo(96976L)
                        jev.program(path) { MenuPhotoVerdict.Menu(1.0) }
                        vision(token, path, eligible = eligible)
                    }
                    val outsider = member(96990L)
                    val outsiderPath = photo(96990L)
                    jev.program(outsiderPath) { MenuPhotoVerdict.Menu(1.0) }
                    vision(outsider, outsiderPath)

                    jev.calls.get() shouldBe 0
                    reader.calls.get() shouldBe 3
                }
            }

            `when`("남의 업로드 경로면") {
                then("400 SCAN-001 이고 Jev·기존 판독 모두 0회다") {
                    member(96977L)
                    val token = member(96978L)
                    val othersPath = photo(96977L)
                    jev.program(othersPath) { MenuPhotoVerdict.Menu(1.0) }

                    vision(token, othersPath, expected = 400).path("code").asText() shouldBe "SCAN-001"

                    jev.calls.get() shouldBe 0
                    reader.calls.get() shouldBe 0
                }
            }

            `when`("같은 키 요청이 Jev 판별 중에 또 들어오면") {
                then("뒤 요청은 409 SCAN-005 이고 Jev 는 한 번만 불린다") {
                    val token = member(96979L)
                    val path = photo(96979L)
                    val key = UUID.randomUUID().toString()
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    jev.program(path) { entered.countDown(); release.await(2, TimeUnit.SECONDS); MenuPhotoVerdict.Menu(0.99) }
                    val pool = Executors.newSingleThreadExecutor()
                    val first = pool.submit<JsonNode> { vision(token, path, key = key) }
                    entered.await(2, TimeUnit.SECONDS) shouldBe true

                    vision(token, path, key = key, expected = 409).path("code").asText() shouldBe "SCAN-005"

                    release.countDown()
                    first.get(5, TimeUnit.SECONDS).path("payload").path("kind").asText() shouldBe "MENU"
                    pool.shutdown()
                    jev.calls.get() shouldBe 1
                }
            }
        }
    }
}
