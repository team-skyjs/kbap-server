package com.kbap.api.scanbatch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.MenuBoardLine
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@IntegrationTest
class ScanBatchRunTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var uploads: UploadedImageJpaRepository
    @Autowired private lateinit var batchSwitch: ScanBatchSwitch
    @Autowired private lateinit var vision: FakeMenuBoardLineExtractor
    @Autowired private lateinit var redis: StringRedisTemplate

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(statement).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "run-${System.nanoTime()}", null)))
        fun token(owner: Member) = "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}"
        fun paths(owner: Member, count: Int) = (1..count).map { uploads.save(UploadedImage(memberId = owner.id, path = "local/images/scans/2026/10/${UUID.randomUUID()}.jpg", contentType = "image/jpeg", sizeBytes = 10)).path }
        fun create(owner: Member, imagePaths: List<String>): String = mapper.readTree(
            mvc.post("/api/scans/batches") {
                header("X-API-Version", "3.3")
                header("Authorization", token(owner))
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("clientRequestId" to UUID.randomUUID().toString(), "lang" to "ko", "currency" to "KRW", "photos" to imagePaths.map { mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to it) }))
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("batchId").asText()
        fun read(owner: Member, batchId: String): JsonNode = mapper.readTree(
            mvc.get("/api/scans/batches/$batchId") { header("X-API-Version", "3.3"); header("Authorization", token(owner)) }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun retry(owner: Member, batchId: String, index: Int, expected: Int = 200): JsonNode = mapper.readTree(
            mvc.post("/api/scans/batches/$batchId/photos/$index/retry") { header("X-API-Version", "3.3"); header("Authorization", token(owner)) }
                .andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        suspend fun settled(owner: Member, batchId: String): JsonNode = eventually(15.seconds) {
            read(owner, batchId).also { payload -> payload.path("photos").none { it.path("state").asText() in setOf("PENDING", "RUNNING") } shouldBe true }
        }
        fun states(payload: JsonNode) = payload.path("photos").map { it.path("state").asText() to it.path("errorReason").asText("") }
        fun scanCount(owner: Member) = sql("SELECT scan_count FROM member WHERE id = ${owner.id}")

        beforeEach {
            TestTables.clearAll(dataSource)
            batchSwitch.enabled = true
            vision.reset()
        }
        afterSpec {
            batchSwitch.enabled = false
            TestTables.clearAll(dataSource)
        }

        given("묶음 생성 뒤 사진별 실행") {
            `when`("성공·인식 실패·메뉴 없음 사진이 섞이면") {
                then("사진마다 상태가 갈리고 1회 차감, 결과는 성공 사진만") {
                    val owner = member()
                    val (ok, broken, blank) = paths(owner, 3)
                    vision.program(ok, listOf(MenuBoardLine("김치찌개", "김치찌개", 9000, null)))
                    vision.failOn(broken)
                    val batchId = create(owner, listOf(ok, broken, blank))
                    val payload = settled(owner, batchId)
                    states(payload) shouldBe listOf("SUCCEEDED" to "", "FAILED" to "RECOGNITION_FAILED", "FAILED" to "NOT_DETECTED")
                    payload.path("photos").map { it.path("retryable").asBoolean() } shouldBe listOf(false, true, true)
                    payload.path("usageRecorded").asBoolean() shouldBe true
                    payload.path("items").map { it.path("rawName").asText() } shouldBe listOf("김치찌개")
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("실패 사진을 재시도해 성공하면") {
                then("추가 차감 없이 결과가 합쳐진다") {
                    val owner = member()
                    val (ok, broken) = paths(owner, 2)
                    vision.program(ok, listOf(MenuBoardLine("김치찌개", "김치찌개", 9000, null)))
                    vision.failOn(broken)
                    val batchId = create(owner, listOf(ok, broken))
                    settled(owner, batchId)
                    vision.program(broken, listOf(MenuBoardLine("김치찌개", "김치찌개", 9000, null), MenuBoardLine("냉면", "냉면", 8000, null)))
                    retry(owner, batchId, 1).path("payload").path("photos")[1].path("state").asText() shouldBe "RUNNING"
                    val payload = settled(owner, batchId)
                    states(payload) shouldBe listOf("SUCCEEDED" to "", "SUCCEEDED" to "")
                    payload.path("items").map { it.path("rawName").asText() to it.path("sources").size() } shouldBe listOf("김치찌개" to 2, "냉면" to 1)
                    scanCount(owner) shouldBe 1
                    payload.path("photos")[1].path("attemptNo").asInt() shouldBe 2
                }
            }
            `when`("재시도할 수 없는 사진(성공)이나 없는 사진을 재시도하면") {
                then("409 SCAN-011, 남의 묶음은 404") {
                    val owner = member()
                    val (ok) = paths(owner, 1)
                    vision.program(ok, listOf(MenuBoardLine("냉면", "냉면", 8000, null)))
                    val batchId = create(owner, listOf(ok))
                    settled(owner, batchId)
                    retry(owner, batchId, 0, expected = 409).path("code").asText() shouldBe "SCAN-011"
                    retry(owner, batchId, 5, expected = 409).path("code").asText() shouldBe "SCAN-011"
                    retry(member(), batchId, 0, expected = 404).path("code").asText() shouldBe "SCAN-010"
                }
            }
            `when`("모든 사진이 비전 장애로 실패하면") {
                then("0회·예약 해제, 재시도는 다시 예약한다") {
                    val owner = member()
                    val (a, b) = paths(owner, 2)
                    vision.unavailableOn(a)
                    vision.unavailableOn(b)
                    val batchId = create(owner, listOf(a, b))
                    states(settled(owner, batchId)) shouldBe listOf("FAILED" to "VISION_UNAVAILABLE", "FAILED" to "VISION_UNAVAILABLE")
                    scanCount(owner) shouldBe 0
                    redis.opsForZSet().score("scan:reservations:${owner.id}", "scan-batch:$batchId") shouldBe null
                    vision.program(a, listOf(MenuBoardLine("냉면", "냉면", 8000, null)))
                    retry(owner, batchId, 0)
                    redis.opsForZSet().score("scan:reservations:${owner.id}", "scan-batch:$batchId") shouldBe null
                    settled(owner, batchId).path("usageRecorded").asBoolean() shouldBe true
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("10장을 보내면") {
                then("묶음당 동시 비전 호출은 3 이하이고, 조회는 비전을 다시 부르지 않는다") {
                    val owner = member()
                    val ten = paths(owner, 10)
                    ten.forEach { vision.program(it, listOf(MenuBoardLine("메뉴$it", "메뉴", 1000, null))) }
                    vision.delayMillis = 150
                    val batchId = create(owner, ten)
                    settled(owner, batchId).path("photos").all { it.path("state").asText() == "SUCCEEDED" } shouldBe true
                    vision.maxConcurrent.get() shouldBeLessThanOrEqual 3
                    val calls = vision.calls.get()
                    calls shouldBe 10
                    read(owner, batchId)
                    vision.calls.get() shouldBe calls
                    scanCount(owner) shouldBe 1
                }
            }
        }
    }
}
