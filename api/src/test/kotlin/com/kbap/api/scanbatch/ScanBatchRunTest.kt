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
import com.kbap.common.domain.scan.ScanOperationPhotoJpaRepository
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
    @Autowired private lateinit var batches: ScanBatchService
    @Autowired private lateinit var photoRows: ScanOperationPhotoJpaRepository
    @Autowired private lateinit var scheduler: org.springframework.scheduling.TaskScheduler
    @Autowired private lateinit var sweeper: ScanBatchSweeper

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(statement).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "run-${System.nanoTime()}", null)))
        fun token(owner: Member) = "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}"
        fun paths(owner: Member, count: Int) = (1..count).map { uploads.save(UploadedImage(memberId = owner.id, path = "local/images/scans/2026/10/${UUID.randomUUID()}.jpg", contentType = "image/jpeg", sizeBytes = 10)).path }
        fun create(owner: Member, imagePaths: List<String>, requestId: UUID = UUID.randomUUID(), photoIds: List<String> = imagePaths.map { UUID.randomUUID().toString() }): String = mapper.readTree(
            mvc.post("/api/scans/batches") {
                header("X-API-Version", "3.3")
                header("Authorization", token(owner))
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("clientRequestId" to requestId.toString(), "lang" to "ko", "currency" to "KRW", "photos" to imagePaths.zip(photoIds).map { (path, id) -> mapOf("photoId" to id, "imagePath" to path) }))
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
        suspend fun retried(owner: Member, batchId: String, index: Int, attemptNo: Int): JsonNode = eventually(15.seconds) {
            read(owner, batchId).also { payload ->
                val photo = payload.path("photos")[index]
                (photo.path("attemptNo").asInt() == attemptNo && photo.path("state").asText() !in setOf("PENDING", "RUNNING")) shouldBe true
            }
        }
        fun states(payload: JsonNode) = payload.path("photos").map { it.path("state").asText() to it.path("errorReason").asText("") }
        fun scanCount(owner: Member) = sql("SELECT scan_count FROM member WHERE id = ${owner.id}")
        fun sweepInline() = ScanBatchSweeper(
            photoRows,
            ScanBatchRunner(batches, batchSwitch, vision, org.springframework.core.task.TaskExecutor { it.run() }, scheduler),
            batchSwitch,
        ).sweep()
        fun expireLease(batchId: String) {
            val now = java.time.LocalDateTime.now()
            val createdAt = java.sql.Timestamp.valueOf(now.minusMinutes(1))
            val startedAt = java.sql.Timestamp.valueOf(now.minusMinutes(3))
            val expiredAt = java.sql.Timestamp.valueOf(now.minusSeconds(5))
            dataSource.connection.use { c -> c.createStatement().use {
                it.executeUpdate("UPDATE scan_operation SET created_at = '$createdAt' WHERE request_key = UUID_TO_BIN('$batchId', 0)")
                it.executeUpdate("UPDATE scan_operation_photo SET lease_until = '$expiredAt' WHERE operation_id = (SELECT id FROM scan_operation WHERE request_key = UUID_TO_BIN('$batchId', 0))")
                it.executeUpdate("UPDATE scan_attempt SET started_at = '$startedAt', deadline_at = '$expiredAt', lease_until = '$expiredAt' WHERE operation_id = (SELECT id FROM scan_operation WHERE request_key = UUID_TO_BIN('$batchId', 0))")
            } }
        }

        beforeEach {
            TestTables.clearAll(dataSource)
            batchSwitch.enabled = true
            batchSwitch.executionEnabled = true
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
                    payload.path("photos").map { it.path("retryable").asBoolean() } shouldBe listOf(false, true, false)
                    retry(owner, batchId, 2, expected = 409).path("code").asText() shouldBe "SCAN-011"
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
                    retry(owner, batchId, 1)
                    val payload = retried(owner, batchId, 1, 2)
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
            `when`("같은 사진이 시도 상한(3회)만큼 실패하면 — 보안 리뷰") {
                then("더 재시도할 수 없어 409 SCAN-011 이고 retryable=false(무차감 유료 호출 무한 반복 차단)") {
                    val owner = member()
                    val (broken) = paths(owner, 1)
                    vision.failOn(broken)
                    val batchId = create(owner, listOf(broken))
                    settled(owner, batchId)
                    (2..3).forEach { attempt ->
                        retry(owner, batchId, 0)
                        retried(owner, batchId, 0, attempt)
                    }
                    val photo = read(owner, batchId).path("photos")[0]
                    photo.path("attemptNo").asInt() shouldBe 3
                    photo.path("retryable").asBoolean() shouldBe false
                    retry(owner, batchId, 0, expected = 409).path("code").asText() shouldBe "SCAN-011"
                    vision.calls.get() shouldBe 3
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
                    retried(owner, batchId, 0, 2).path("usageRecorded").asBoolean() shouldBe true
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("같은 묶음 생성이 다시 와서 재dispatch 되면 — Codex P2(#439)") {
                then("묶음당 동시 비전 호출은 여전히 3 이하, 사진마다 한 번만 실행된다") {
                    val owner = member()
                    val six = paths(owner, 6)
                    six.forEach { vision.program(it, listOf(MenuBoardLine("메뉴$it", "메뉴", 1000, null))) }
                    vision.delayMillis = 300
                    val requestId = UUID.randomUUID()
                    val photoIds = six.map { UUID.randomUUID().toString() }
                    val batchId = create(owner, six, requestId, photoIds)
                    create(owner, six, requestId, photoIds) shouldBe batchId
                    create(owner, six, requestId, photoIds) shouldBe batchId
                    settled(owner, batchId).path("photos").all { it.path("state").asText() == "SUCCEEDED" } shouldBe true
                    vision.maxConcurrent.get() shouldBeLessThanOrEqual 3
                    vision.calls.get() shouldBe 6
                }
            }
            `when`("두 회원이 같은 clientRequestId 로 동시에 묶음을 만들면 — 보안 리뷰") {
                then("각자 자기 묶음이 자기 권한으로 실행된다(실행 작업이 회원별로 분리)") {
                    val requestId = UUID.randomUUID()
                    val owners = listOf(member(), member())
                    val batches = owners.map { owner ->
                        val owned = paths(owner, 2)
                        owned.forEach { vision.program(it, listOf(MenuBoardLine("메뉴$it", "메뉴", 1000, null))) }
                        owner to owned
                    }
                    vision.delayMillis = 200
                    batches.forEach { (owner, owned) -> create(owner, owned, requestId) shouldBe requestId.toString() }
                    batches.forEach { (owner, owned) ->
                        val payload = settled(owner, requestId.toString())
                        payload.path("photos").all { it.path("state").asText() == "SUCCEEDED" } shouldBe true
                        payload.path("items").map { it.path("rawName").asText() }.toSet() shouldBe owned.map { "메뉴$it" }.toSet()
                    }
                }
            }
            `when`("묶음 전용 실행기가 작업을 한 번 거절하면 — Codex P1(#440)") {
                then("작업을 버리지 않고 잠시 뒤 다시 띄워 사진이 끝내 실행된다") {
                    val owner = member()
                    val (only) = paths(owner, 1)
                    vision.program(only, listOf(MenuBoardLine("냉면", "냉면", 8000, null)))
                    batchSwitch.executionEnabled = false
                    val batchId = create(owner, listOf(only))
                    batchSwitch.executionEnabled = true
                    val rejections = java.util.concurrent.atomic.AtomicInteger()
                    val flaky = org.springframework.core.task.TaskExecutor { task ->
                        if (rejections.getAndIncrement() == 0) throw org.springframework.core.task.TaskRejectedException("포화(테스트)")
                        Thread(task).start()
                    }
                    ScanBatchRunner(batches, batchSwitch, vision, flaky, scheduler).dispatch(owner.id, UUID.fromString(batchId), listOf(0))
                    settled(owner, batchId).path("photos")[0].path("state").asText() shouldBe "SUCCEEDED"
                    (rejections.get() >= 2) shouldBe true
                }
            }
            `when`("생성 뒤 실행 작업이 사라졌으면(재시작·배포) — Codex P1(#440)") {
                then("주기 스윕이 DB 의 PENDING 사진을 다시 띄워 실행한다") {
                    val owner = member()
                    val (only) = paths(owner, 1)
                    vision.program(only, listOf(MenuBoardLine("냉면", "냉면", 8000, null)))
                    batchSwitch.executionEnabled = false
                    val batchId = create(owner, listOf(only))
                    batchSwitch.executionEnabled = true
                    sql("SELECT COUNT(*) FROM scan_attempt") shouldBe 0
                    dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate("UPDATE scan_operation SET created_at = created_at - INTERVAL 1 MINUTE WHERE request_key = UUID_TO_BIN('$batchId', 0)") } }
                    sweeper.sweep()
                    settled(owner, batchId).path("photos")[0].path("state").asText() shouldBe "SUCCEEDED"
                }
            }
            `when`("RUNNING 사진의 실행 lease가 만료된 뒤 작업이 사라졌으면") {
                then("주기 스윕이 사진을 다시 실행해 묶음을 완료한다") {
                    val owner = member()
                    val (only) = paths(owner, 1)
                    vision.program(only, listOf(MenuBoardLine("냉면", "냉면", 8000, null)))
                    batchSwitch.executionEnabled = false
                    val batchId = create(owner, listOf(only))
                    batches.startAttempt(owner.id, UUID.fromString(batchId), 0)!!.attemptNo shouldBe 1
                    expireLease(batchId)
                    batchSwitch.executionEnabled = true
                    sweepInline()
                    val photo = eventually(15.seconds) {
                        read(owner, batchId).path("photos")[0].also { it.path("state").asText() shouldBe "SUCCEEDED" }
                    }
                    photo.path("state").asText() shouldBe "SUCCEEDED"
                    photo.path("attemptNo").asInt() shouldBe 2
                    vision.calls.get() shouldBe 1
                }
            }
            `when`("RUNNING 사진의 실행 lease가 아직 유효하면") {
                then("주기 스윕이 중복 실행하지 않는다") {
                    val owner = member()
                    val (only) = paths(owner, 1)
                    batchSwitch.executionEnabled = false
                    val batchId = create(owner, listOf(only))
                    val claim = batches.startAttempt(owner.id, UUID.fromString(batchId), 0)!!
                    claim.attemptNo shouldBe 1
                    dataSource.connection.use { c -> c.createStatement().use {
                        it.executeUpdate("UPDATE scan_operation SET created_at = created_at - INTERVAL 1 MINUTE WHERE request_key = UUID_TO_BIN('$batchId', 0)")
                    } }
                    batchSwitch.executionEnabled = true
                    sweepInline()
                    val photo = read(owner, batchId).path("photos")[0]
                    photo.path("state").asText() shouldBe "RUNNING"
                    photo.path("attemptNo").asInt() shouldBe 1
                    vision.calls.get() shouldBe 0
                    batches.recordSuccess(claim, listOf(MenuBoardLine("냉면", "냉면", 8000, null))) shouldBe true
                    sweepInline()
                    read(owner, batchId).path("photos")[0].path("state").asText() shouldBe "SUCCEEDED"
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("만료된 RUNNING 사진이 일일 무차감 시도 한도에 걸리면") {
                then("재디스패치 대신 ATTEMPT_LIMIT 으로 종결한다") {
                    val owner = member()
                    val (only) = paths(owner, 1)
                    batchSwitch.executionEnabled = false
                    val batchId = create(owner, listOf(only))
                    batches.startAttempt(owner.id, UUID.fromString(batchId), 0)!!.attemptNo shouldBe 1
                    expireLease(batchId)
                    dataSource.connection.use { c -> c.createStatement().use {
                        it.executeUpdate("""INSERT INTO scan_attempt (operation_id, member_id, photo_row_id, unit_key, phase, target_manifest_revision, attempt_no, claim_token, reservation_epoch, state, started_at, deadline_at, lease_until)
                            SELECT a.operation_id, a.member_id, a.photo_row_id, CONCAT('seed:', n.n), a.phase, 1, 1, a.claim_token, 0, 'FAILED', a.started_at, a.deadline_at, a.lease_until
                            FROM scan_attempt a JOIN (WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 29) SELECT n FROM s) n WHERE a.member_id = ${owner.id}""")
                    } }
                    batchSwitch.executionEnabled = true
                    sweepInline()
                    val photo = eventually(5.seconds) {
                        read(owner, batchId).path("photos")[0].also { it.path("errorReason").asText() shouldBe "ATTEMPT_LIMIT" }
                    }
                    photo.path("state").asText() shouldBe "FAILED"
                    photo.path("retryable").asBoolean() shouldBe false
                    photo.path("attemptNo").asInt() shouldBe 1
                    vision.calls.get() shouldBe 0
                }
            }
            `when`("RUNNING 사진의 마지막 시도 lease가 만료되면") {
                then("실패로 종결하고 미차감 묶음의 예약을 해제한다") {
                    val owner = member()
                    val (only) = paths(owner, 1)
                    batchSwitch.executionEnabled = false
                    val batchId = create(owner, listOf(only))
                    repeat(2) { index ->
                        batches.startAttempt(owner.id, UUID.fromString(batchId), 0)!!.attemptNo shouldBe index + 1
                        expireLease(batchId)
                    }
                    val lateClaim = batches.startAttempt(owner.id, UUID.fromString(batchId), 0)!!
                    lateClaim.attemptNo shouldBe 3
                    expireLease(batchId)
                    batchSwitch.executionEnabled = true
                    sweepInline()
                    val photo = read(owner, batchId).path("photos")[0]
                    photo.path("state").asText() shouldBe "FAILED"
                    photo.path("errorReason").asText() shouldBe "LEASE_EXPIRED"
                    photo.path("retryable").asBoolean() shouldBe false
                    photo.path("attemptNo").asInt() shouldBe 3
                    sql("SELECT COUNT(*) FROM scan_operation WHERE request_key = UUID_TO_BIN('$batchId', 0) AND reservation_state = 'RELEASED'") shouldBe 1
                    sql("SELECT COUNT(*) FROM scan_attempt WHERE operation_id = (SELECT id FROM scan_operation WHERE request_key = UUID_TO_BIN('$batchId', 0)) AND attempt_no = 3 AND state = 'FAILED' AND call_slot_held = 0") shouldBe 1
                    batches.recordSuccess(lateClaim, listOf(MenuBoardLine("냉면", "냉면", 8000, null))) shouldBe false
                    scanCount(owner) shouldBe 0
                    sql("SELECT COUNT(*) FROM scan_usage WHERE operation_id = (SELECT id FROM scan_operation WHERE request_key = UUID_TO_BIN('$batchId', 0))") shouldBe 0
                }
            }
            `when`("일일 무차감 시도 상한에 걸린 회원의 사진을 실행하려 하면 — Codex P2(#440)") {
                then("사진이 PENDING 에 남지 않고 FAILED(ATTEMPT_LIMIT)·재시도 불가로 끝난다") {
                    val owner = member()
                    val (first, second) = paths(owner, 2)
                    vision.failOn(first)
                    val seeded = create(owner, listOf(first))
                    settled(owner, seeded)
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.executeUpdate("""INSERT INTO scan_attempt (operation_id, member_id, photo_row_id, unit_key, phase, target_manifest_revision, attempt_no, claim_token, reservation_epoch, state, started_at, deadline_at, lease_until)
                                SELECT a.operation_id, a.member_id, a.photo_row_id, CONCAT('seed:', n.n), a.phase, 1, 1, a.claim_token, 0, 'FAILED', a.started_at, a.deadline_at, a.lease_until
                                FROM scan_attempt a JOIN (WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 29) SELECT n FROM s) n WHERE a.member_id = ${owner.id}""")
                        }
                    }
                    vision.program(second, listOf(MenuBoardLine("냉면", "냉면", 8000, null)))
                    val blocked = create(owner, listOf(second))
                    val photo = settled(owner, blocked).path("photos")[0]
                    photo.path("state").asText() shouldBe "FAILED"
                    photo.path("errorReason").asText() shouldBe "ATTEMPT_LIMIT"
                    photo.path("retryable").asBoolean() shouldBe false
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
