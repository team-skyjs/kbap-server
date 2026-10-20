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
import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class ScanBatchTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var uploads: UploadedImageJpaRepository
    @Autowired private lateinit var batchSwitch: ScanBatchSwitch
    @Autowired private lateinit var batches: ScanBatchService
    @Autowired private lateinit var reservations: ScanReservationStore
    @Autowired private lateinit var redis: StringRedisTemplate

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                if (statement.trimStart().startsWith("SELECT")) st.executeQuery(statement).use { rs -> if (rs.next()) rs.getLong(1) else -1L }
                else st.executeUpdate(statement).toLong()
            }
        }
        fun member(tag: String, scanCount: Int = 0): Member {
            val m = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "$tag-${System.nanoTime()}", null)))
            if (scanCount > 0) sql("UPDATE member SET scan_count = $scanCount WHERE id = ${m.id}")
            return m
        }
        fun upload(owner: Member, segment: String = "scans"): String =
            uploads.save(UploadedImage(memberId = owner.id, path = "local/images/$segment/2026/10/${owner.id}_${UUID.randomUUID()}.jpg", contentType = "image/jpeg", sizeBytes = 1024)).path
        fun photos(owner: Member, count: Int) = (1..count).map { mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to upload(owner)) }
        fun body(requestId: UUID, photos: List<Map<String, String>>) = mapOf("clientRequestId" to requestId.toString(), "lang" to "ko", "currency" to "KRW", "photos" to photos)

        fun create(owner: Member, payload: Map<String, Any>, expected: Int = 200, version: String = "3.3"): JsonNode = mapper.readTree(
            mvc.post("/api/scans/batches") {
                header("X-API-Version", version)
                header("Authorization", "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(payload)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun read(owner: Member, batchId: String, expected: Int = 200): JsonNode = mapper.readTree(
            mvc.get("/api/scans/batches/$batchId") {
                header("X-API-Version", "3.3")
                header("Authorization", "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}")
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun cancel(owner: Member, batchId: String): JsonNode = mapper.readTree(
            mvc.post("/api/scans/batches/$batchId/cancel") {
                header("X-API-Version", "3.3")
                header("Authorization", "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun reserved(owner: Member, batchId: String): Boolean = redis.opsForZSet().score("scan:reservations:${owner.id}", "scan-batch:$batchId") != null
        fun scanCount(owner: Member) = sql("SELECT scan_count FROM member WHERE id = ${owner.id}")
        fun usages(batchId: String) = sql("SELECT COUNT(*) FROM scan_usage u JOIN scan_operation o ON o.id = u.operation_id WHERE o.request_key = UUID_TO_BIN('$batchId', 0)")
        val extraction = listOf(MenuBoardLine("김치찌개", "김치찌개", 9000, null))
        fun newBatch(owner: Member, count: Int = 2): String = create(owner, body(UUID.randomUUID(), photos(owner, count))).path("payload").path("batchId").asText()

        beforeEach {
            TestTables.clearAll(dataSource)
            batchSwitch.enabled = true
            batchSwitch.executionEnabled = false
        }
        afterSpec {
            batchSwitch.enabled = false
            batchSwitch.executionEnabled = true
            TestTables.clearAll(dataSource)
        }

        given("묶음 생성(3.3)") {
            `when`("플래그가 꺼져 있으면") {
                then("404 SCAN-010 이고 묶음·예약이 생기지 않는다") {
                    batchSwitch.enabled = false
                    val owner = member("batch-off")
                    create(owner, body(UUID.randomUUID(), photos(owner, 1)), expected = 404).path("code").asText() shouldBe "SCAN-010"
                    sql("SELECT COUNT(*) FROM scan_operation") shouldBe 0
                }
            }
            `when`("1·2·5·10장을 보내면") {
                then("사진이 선택 순서대로 PENDING 이고 공통 잔량 예약이 하나 잡힌다") {
                    listOf(1, 2, 5, 10).forEach { count ->
                        val owner = member("batch-$count")
                        val sent = photos(owner, count)
                        val payload = create(owner, body(UUID.randomUUID(), sent)).path("payload")
                        payload.path("lifecycle").asText() shouldBe "ACTIVE"
                        payload.path("usageRecorded").asBoolean() shouldBe false
                        payload.path("photos").map { it.path("photoId").asText() } shouldBe sent.map { it["photoId"] }
                        payload.path("photos").map { it.path("index").asInt() } shouldBe (0 until count).toList()
                        payload.path("photos").all { it.path("state").asText() == "PENDING" } shouldBe true
                        reserved(owner, payload.path("batchId").asText()) shouldBe true
                    }
                }
            }
            `when`("11장이거나 0장이면") {
                then("SCAN-009·400 으로 막는다") {
                    val owner = member("batch-limit")
                    create(owner, body(UUID.randomUUID(), photos(owner, 11)), expected = 400).path("code").asText() shouldBe "SCAN-009"
                    create(owner, body(UUID.randomUUID(), emptyList()), expected = 400)
                    sql("SELECT COUNT(*) FROM scan_operation") shouldBe 0
                }
            }
            `when`("사진 id·경로가 겹치거나 남의 업로드·스캔 목적이 아닌 업로드면") {
                then("400 으로 막는다(INVALID_REQUEST·SCAN-001)") {
                    val owner = member("batch-own")
                    val other = member("batch-other")
                    val path = upload(owner)
                    create(owner, body(UUID.randomUUID(), listOf(mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to path), mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to path))), expected = 400)
                    val id = UUID.randomUUID().toString()
                    create(owner, body(UUID.randomUUID(), listOf(mapOf("photoId" to id, "imagePath" to upload(owner)), mapOf("photoId" to id, "imagePath" to upload(owner)))), expected = 400)
                    create(owner, body(UUID.randomUUID(), listOf(mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to upload(other)))), expected = 400).path("code").asText() shouldBe "SCAN-001"
                    create(owner, body(UUID.randomUUID(), listOf(mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to upload(owner, "review")))), expected = 400).path("code").asText() shouldBe "SCAN-001"
                    sql("SELECT COUNT(*) FROM scan_operation") shouldBe 0
                }
            }
            `when`("같은 clientRequestId 로 다시 보내면") {
                then("같은 내용은 같은 묶음, 다른 내용은 409 SCAN-005") {
                    val owner = member("batch-idem")
                    val requestId = UUID.randomUUID()
                    val sent = photos(owner, 2)
                    val first = create(owner, body(requestId, sent)).path("payload").path("batchId").asText()
                    create(owner, body(requestId, sent)).path("payload").path("batchId").asText() shouldBe first
                    create(owner, body(requestId, sent.take(1)), expected = 409).path("code").asText() shouldBe "SCAN-005"
                    sql("SELECT COUNT(*) FROM scan_operation") shouldBe 1
                }
            }
            `when`("같은 clientRequestId 생성이 동시에 두 번 오면 — Codex P2") {
                then("둘 다 같은 묶음을 받고 묶음·예약은 하나다") {
                    val owner = member("batch-concurrent")
                    val requestId = UUID.randomUUID()
                    val payload = body(requestId, photos(owner, 2))
                    val start = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    val futures = (1..2).map { pool.submit<String> { start.await(); create(owner, payload).path("payload").path("batchId").asText() } }
                    start.countDown()
                    futures.map { it.get(30, TimeUnit.SECONDS) } shouldBe listOf(requestId.toString(), requestId.toString())
                    pool.shutdown()
                    sql("SELECT COUNT(*) FROM scan_operation") shouldBe 1
                    redis.opsForZSet().size("scan:reservations:${owner.id}") shouldBe 1
                }
            }
            `when`("멱등 재생 중에 다른 트랜잭션이 그 묶음 행을 잠그고 있으면 — Codex P2(잠금 역전)") {
                then("재생은 묶음 행 잠금을 기다리지 않고 바로 같은 묶음을 돌려준다(회원 → 묶음 역순 잠금 없음)") {
                    val owner = member("batch-replay-lock")
                    val requestId = UUID.randomUUID()
                    val payload = body(requestId, photos(owner, 1))
                    create(owner, payload)
                    dataSource.connection.use { holder ->
                        holder.autoCommit = false
                        holder.createStatement().use { it.executeQuery("SELECT id FROM scan_operation WHERE request_key = UUID_TO_BIN('$requestId', 0) FOR UPDATE").close() }
                        val pool = Executors.newSingleThreadExecutor()
                        val replay = pool.submit<String> { create(owner, payload).path("payload").path("batchId").asText() }
                        replay.get(5, TimeUnit.SECONDS) shouldBe requestId.toString()
                        holder.rollback()
                        pool.shutdown()
                    }
                }
            }
            `when`("플래그를 끈 뒤 기존 묶음을 조회·취소하면 — Codex P2") {
                then("생성과 같이 404 SCAN-010 이다") {
                    val owner = member("batch-flag-off")
                    val batchId = newBatch(owner)
                    batchSwitch.enabled = false
                    read(owner, batchId, expected = 404).path("code").asText() shouldBe "SCAN-010"
                    mvc.post("/api/scans/batches/$batchId/cancel") {
                        header("X-API-Version", "3.3")
                        header("Authorization", "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}")
                    }.andExpect { status { isNotFound() } }
                    batches.startAttempt(owner.id, UUID.fromString(batchId), 0) shouldBe null
                }
            }
            `when`("결과 보관(7일)이 지난 뒤 같은 clientRequestId 로 다시 보내면 — Codex P2(#439)") {
                then("만료된 결과를 돌려주지 않고 410 SCAN-013(앱은 새 clientRequestId 로 다시 만든다)") {
                    val owner = member("batch-result-expired")
                    val requestId = UUID.randomUUID()
                    val payload = body(requestId, photos(owner, 1))
                    create(owner, payload)
                    sql("UPDATE scan_operation SET expires_at = expires_at - INTERVAL 8 DAY, result_expires_at = result_expires_at - INTERVAL 8 DAY WHERE request_key = UUID_TO_BIN('$requestId', 0)")
                    create(owner, payload, expected = 410).path("code").asText() shouldBe "SCAN-013"
                }
            }
            `when`("무료 횟수가 남지 않았으면") {
                then("403 SCAN-004 이고 묶음이 생기지 않는다") {
                    val owner = member("batch-exhausted", scanCount = Member.FREE_SCAN_LIMIT)
                    create(owner, body(UUID.randomUUID(), photos(owner, 1)), expected = 403).path("code").asText() shouldBe "SCAN-004"
                    sql("SELECT COUNT(*) FROM scan_operation") shouldBe 0
                }
            }
            `when`("마지막 한 회를 묶음이 예약하면") {
                then("단일 스캔·가공식품 예약은 같은 잔량에서 LIMIT_EXCEEDED 다") {
                    val owner = member("batch-shared", scanCount = Member.FREE_SCAN_LIMIT - 1)
                    newBatch(owner)
                    reservations.reserve(owner.id, "single-${UUID.randomUUID()}", Member.FREE_SCAN_LIMIT - 1, Member.FREE_SCAN_LIMIT) shouldBe ScanReservationResult.LIMIT_EXCEEDED
                }
            }
            `when`("묶음 예약 뒤 짧은 단일 스캔 예약이 들어오면") {
                then("회원 예약 키의 만료가 묶음 실행 창보다 짧아지지 않는다") {
                    val owner = member("batch-ttl")
                    newBatch(owner)
                    reservations.reserve(owner.id, "single-${UUID.randomUUID()}", 0, Member.FREE_SCAN_LIMIT)
                    redis.getExpire("scan:reservations:${owner.id}", TimeUnit.SECONDS) shouldBeGreaterThan Duration.ofMinutes(10).seconds
                }
            }
        }

        given("묶음 차감(첫 성공 1회)") {
            `when`("두 사진이 차례로 성공하면") {
                then("1회만 차감되고 usage 1건, 예약은 풀린다") {
                    val owner = member("batch-charge")
                    val batchId = newBatch(owner)
                    batches.recordSuccess(batches.startAttempt(owner.id, UUID.fromString(batchId), 0)!!, extraction) shouldBe true
                    batches.recordSuccess(batches.startAttempt(owner.id, UUID.fromString(batchId), 1)!!, extraction) shouldBe true
                    scanCount(owner) shouldBe 1
                    usages(batchId) shouldBe 1
                    reserved(owner, batchId) shouldBe false
                    val payload = read(owner, batchId).path("payload")
                    payload.path("usageRecorded").asBoolean() shouldBe true
                    payload.path("photos").map { it.path("state").asText() } shouldBe listOf("SUCCEEDED", "SUCCEEDED")
                }
            }
            `when`("같은 성공 콜백이 두 번 오면 — Codex P2") {
                then("두 번째는 폐기되고 이미 SUCCEEDED 인 시도 기록은 그대로다") {
                    val owner = member("batch-dup-callback")
                    val batchId = UUID.fromString(newBatch(owner))
                    val claim = batches.startAttempt(owner.id, batchId, 0)!!
                    batches.recordSuccess(claim, extraction) shouldBe true
                    batches.recordSuccess(claim, extraction) shouldBe false
                    sql("SELECT COUNT(*) FROM scan_attempt WHERE id = ${claim.attemptId} AND state = 'SUCCEEDED'") shouldBe 1
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("두 사진의 첫 성공이 동시에 확정되면") {
                then("차감은 정확히 1회다") {
                    val owner = member("batch-race")
                    val batchId = UUID.fromString(newBatch(owner))
                    val claims = listOf(batches.startAttempt(owner.id, batchId, 0)!!, batches.startAttempt(owner.id, batchId, 1)!!)
                    val start = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    val futures = claims.map { claim -> pool.submit<Boolean> { start.await(); batches.recordSuccess(claim, extraction) } }
                    start.countDown()
                    futures.map { it.get(30, TimeUnit.SECONDS) } shouldBe listOf(true, true)
                    pool.shutdown()
                    scanCount(owner) shouldBe 1
                    usages(batchId.toString()) shouldBe 1
                }
            }
            `when`("한 장은 성공하고 한 장은 실패하면") {
                then("1회 차감, 실패 사진은 재시도 가능으로 남는다") {
                    val owner = member("batch-partial")
                    val batchId = UUID.fromString(newBatch(owner))
                    batches.recordSuccess(batches.startAttempt(owner.id, batchId, 0)!!, extraction)
                    batches.recordFailure(batches.startAttempt(owner.id, batchId, 1)!!, "RECOGNITION_FAILED")
                    scanCount(owner) shouldBe 1
                    val photo = read(owner, batchId.toString()).path("payload").path("photos")[1]
                    photo.path("state").asText() shouldBe "FAILED"
                    photo.path("retryable").asBoolean() shouldBe true
                }
            }
            `when`("모든 사진이 실패하면") {
                then("0회이고 예약이 풀린다") {
                    val owner = member("batch-allfail")
                    val batchId = UUID.fromString(newBatch(owner))
                    batches.recordFailure(batches.startAttempt(owner.id, batchId, 0)!!, "RECOGNITION_FAILED")
                    batches.recordFailure(batches.startAttempt(owner.id, batchId, 1)!!, "NOT_DETECTED")
                    scanCount(owner) shouldBe 0
                    usages(batchId.toString()) shouldBe 0
                    reserved(owner, batchId.toString()) shouldBe false
                }
            }
            `when`("전부 실패한 뒤 같은 묶음 사진을 다시 시도하면") {
                then("예약을 다시 잡고, 잔량이 없으면 SCAN-004 로 시도하지 않는다") {
                    val owner = member("batch-retry")
                    val batchId = UUID.fromString(newBatch(owner, count = 1))
                    batches.recordFailure(batches.startAttempt(owner.id, batchId, 0)!!, "RECOGNITION_FAILED")
                    reserved(owner, batchId.toString()) shouldBe false
                    batches.startAttempt(owner.id, batchId, 0)!!.attemptNo shouldBe 2
                    reserved(owner, batchId.toString()) shouldBe true

                    val exhausted = member("batch-retry-exhausted")
                    val exhaustedBatch = UUID.fromString(newBatch(exhausted, count = 1))
                    batches.recordFailure(batches.startAttempt(exhausted.id, exhaustedBatch, 0)!!, "RECOGNITION_FAILED")
                    sql("UPDATE member SET scan_count = ${Member.FREE_SCAN_LIMIT} WHERE id = ${exhausted.id}")
                    io.kotest.assertions.throwables.shouldThrow<com.kbap.common.core.error.BusinessException> { batches.startAttempt(exhausted.id, exhaustedBatch, 0) }.errorCode shouldBe com.kbap.common.core.error.ErrorCode.SCAN_LIMIT_EXCEEDED
                }
            }
            `when`("실행 창이 지난 묶음을 취소하면 — Codex P2") {
                then("EXPIRED 상태와 사진은 그대로 두고 예약만 정리한다") {
                    val owner = member("batch-expired-cancel")
                    val batchId = newBatch(owner)
                    sql("UPDATE scan_operation SET expires_at = expires_at - INTERVAL 1 HOUR WHERE request_key = UUID_TO_BIN('$batchId', 0)")
                    val payload = cancel(owner, batchId).path("payload")
                    payload.path("lifecycle").asText() shouldBe "EXPIRED"
                    payload.path("photos").map { it.path("state").asText() } shouldBe listOf("PENDING", "PENDING")
                    sql("SELECT COUNT(*) FROM scan_operation WHERE lifecycle = 'ACTIVE' AND request_key = UUID_TO_BIN('$batchId', 0)") shouldBe 1
                    reserved(owner, batchId) shouldBe false
                }
            }
            `when`("실행 창이 거의 끝날 때 전부 실패 뒤 재시도하면 — Codex P2") {
                then("다시 잡는 예약은 남은 실행 창까지만 유지된다") {
                    val owner = member("batch-retry-ttl")
                    val batchId = UUID.fromString(newBatch(owner, count = 1))
                    batches.recordFailure(batches.startAttempt(owner.id, batchId, 0)!!, "RECOGNITION_FAILED")
                    sql("UPDATE scan_operation SET expires_at = expires_at - INTERVAL 14 MINUTE WHERE request_key = UUID_TO_BIN('$batchId', 0)")
                    batches.startAttempt(owner.id, batchId, 0) shouldNotBe null
                    val expiresInMillis = redis.opsForZSet().score("scan:reservations:${owner.id}", "scan-batch:$batchId")!!.toLong() - System.currentTimeMillis()
                    (expiresInMillis <= Duration.ofSeconds(70).toMillis()) shouldBe true
                }
            }
            `when`("차감 없는 묶음 시도가 최근 24시간 30회에 이르면 — 공부(비용 상한)") {
                then("새 시도는 429 SCAN-012 로 막히고, 차감된 묶음의 시도는 세지 않는다") {
                    val owner = member("batch-attempt-cap")
                    val batchId = UUID.fromString(newBatch(owner, count = 2))
                    val seed = batches.startAttempt(owner.id, batchId, 0)!!
                    sql("""INSERT INTO scan_attempt (operation_id, member_id, photo_row_id, unit_key, phase, target_manifest_revision, attempt_no, claim_token, reservation_epoch, state, started_at, deadline_at, lease_until)
                        SELECT a.operation_id, a.member_id, a.photo_row_id, CONCAT('seed:', n.n), a.phase, 1, 1, a.claim_token, 0, 'FAILED', a.started_at, a.deadline_at, a.lease_until
                        FROM scan_attempt a JOIN (WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 29) SELECT n FROM s) n WHERE a.id = ${seed.attemptId}""")
                    io.kotest.assertions.throwables.shouldThrow<com.kbap.common.core.error.BusinessException> { batches.startAttempt(owner.id, batchId, 1) }.errorCode shouldBe com.kbap.common.core.error.ErrorCode.SCAN_BATCH_ATTEMPT_LIMIT
                    batches.recordSuccess(seed, extraction) shouldBe true
                    batches.startAttempt(owner.id, batchId, 1) shouldNotBe null
                }
            }
            `when`("결과 전에 취소하면") {
                then("0회, 사진은 CANCELLED, 늦게 온 결과는 버려진다") {
                    val owner = member("batch-cancel")
                    val batchId = UUID.fromString(newBatch(owner))
                    val claim = batches.startAttempt(owner.id, batchId, 0)!!
                    val payload = cancel(owner, batchId.toString()).path("payload")
                    payload.path("lifecycle").asText() shouldBe "CANCELLED"
                    payload.path("photos").map { it.path("state").asText() } shouldBe listOf("CANCELLED", "CANCELLED")
                    batches.recordSuccess(claim, extraction) shouldBe false
                    scanCount(owner) shouldBe 0
                    usages(batchId.toString()) shouldBe 0
                    reserved(owner, batchId.toString()) shouldBe false
                    cancel(owner, batchId.toString()).path("payload").path("lifecycle").asText() shouldBe "CANCELLED"
                    batches.startAttempt(owner.id, batchId, 1) shouldBe null
                }
            }
            `when`("취소와 첫 성공 확정이 동시에 오면 — 공부 참고") {
                then("어느 쪽이 먼저든 차감·결과·상태가 서로 맞는다") {
                    repeat(3) {
                        val owner = member("batch-cancel-race-$it")
                        val batchId = UUID.fromString(newBatch(owner, count = 1))
                        val claim = batches.startAttempt(owner.id, batchId, 0)!!
                        val start = CountDownLatch(1)
                        val pool = Executors.newFixedThreadPool(2)
                        val success = pool.submit<Boolean> { start.await(); batches.recordSuccess(claim, extraction) }
                        val cancelled = pool.submit<String> { start.await(); batches.cancelBatch(owner.id, batchId.toString()).lifecycle.name }
                        start.countDown()
                        val committed = success.get(30, TimeUnit.SECONDS)
                        cancelled.get(30, TimeUnit.SECONDS) shouldBe "CANCELLED"
                        pool.shutdown()
                        val expected = if (committed) 1L else 0L
                        usages(batchId.toString()) shouldBe expected
                        scanCount(owner) shouldBe expected
                        read(owner, batchId.toString()).path("payload").path("photos")[0].path("state").asText() shouldBe (if (committed) "SUCCEEDED" else "CANCELLED")
                        reserved(owner, batchId.toString()) shouldBe false
                    }
                }
            }
            `when`("성공 결과 뒤에 취소하면") {
                then("차감 1회는 유지되고 남은 사진은 CANCELLED") {
                    val owner = member("batch-cancel-after")
                    val batchId = UUID.fromString(newBatch(owner))
                    batches.recordSuccess(batches.startAttempt(owner.id, batchId, 0)!!, extraction)
                    val payload = cancel(owner, batchId.toString()).path("payload")
                    payload.path("usageRecorded").asBoolean() shouldBe true
                    payload.path("photos").map { it.path("state").asText() } shouldBe listOf("SUCCEEDED", "CANCELLED")
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("같은 사진의 시도가 다시 시작된 뒤 이전 시도 결과가 늦게 오면") {
                then("이전 시도 결과는 버려지고 현재 시도만 확정된다") {
                    val owner = member("batch-stale")
                    val batchId = UUID.fromString(newBatch(owner))
                    val stale = batches.startAttempt(owner.id, batchId, 0)!!
                    batches.recordFailure(stale, "TIMEOUT")
                    val current = batches.startAttempt(owner.id, batchId, 0)!!
                    current.attemptNo shouldBe stale.attemptNo + 1
                    batches.recordSuccess(stale, extraction) shouldBe false
                    batches.recordSuccess(current, extraction) shouldBe true
                    scanCount(owner) shouldBe 1
                }
            }
            `when`("시도 기한(lease)이 지난 뒤 결과가 도착하면 — Codex P1") {
                then("대체 시도가 없어도 확정하지 않고 FAILED(LEASE_EXPIRED)·재시도 가능, 차감 0") {
                    val owner = member("batch-lease")
                    val batchId = UUID.fromString(newBatch(owner))
                    val claim = batches.startAttempt(owner.id, batchId, 0)!!
                    sql("UPDATE scan_operation_photo SET lease_until = '2000-01-01 00:00:00' WHERE id = ${claim.photoRowId}")
                    batches.recordSuccess(claim, extraction) shouldBe false
                    scanCount(owner) shouldBe 0
                    usages(batchId.toString()) shouldBe 0
                    val photo = read(owner, batchId.toString()).path("payload").path("photos")[0]
                    photo.path("state").asText() shouldBe "FAILED"
                    photo.path("errorReason").asText() shouldBe "LEASE_EXPIRED"
                    photo.path("retryable").asBoolean() shouldBe true
                }
            }
            `when`("확정 시점에 회원 잔량이 이미 소진돼 있으면(예약 만료 뒤 다른 경로가 소진)") {
                then("결과를 저장하지 않고 그 사진은 QUOTA_EXHAUSTED 실패, 차감 0") {
                    val owner = member("batch-quota")
                    val batchId = UUID.fromString(newBatch(owner))
                    val claim = batches.startAttempt(owner.id, batchId, 0)!!
                    sql("UPDATE member SET scan_count = ${Member.FREE_SCAN_LIMIT} WHERE id = ${owner.id}")
                    batches.recordSuccess(claim, extraction) shouldBe false
                    scanCount(owner) shouldBe Member.FREE_SCAN_LIMIT.toLong()
                    usages(batchId.toString()) shouldBe 0
                    val payload = read(owner, batchId.toString()).path("payload")
                    payload.path("photos")[0].path("state").asText() shouldBe "FAILED"
                    payload.path("photos")[0].path("errorReason").asText() shouldBe "QUOTA_EXHAUSTED"
                    sql("SELECT COUNT(*) FROM scan_operation_result r JOIN scan_operation o ON o.id = r.operation_id WHERE o.request_key = UUID_TO_BIN('$batchId', 0)") shouldBe 0
                    payload.path("lifecycle").asText() shouldBe "QUOTA_EXHAUSTED"
                    payload.path("photos")[1].path("state").asText() shouldBe "CANCELLED"
                    batches.startAttempt(owner.id, batchId, 1) shouldBe null
                    reserved(owner, batchId.toString()) shouldBe false
                }
            }
            `when`("무제한 해제 회원이면") {
                then("첫 성공에 usage 는 남지만 해제 상태는 그대로다") {
                    val owner = member("batch-unlocked", scanCount = Member.FREE_SCAN_LIMIT)
                    sql("UPDATE member SET scan_unlocked = 1 WHERE id = ${owner.id}")
                    val batchId = UUID.fromString(newBatch(owner))
                    batches.recordSuccess(batches.startAttempt(owner.id, batchId, 0)!!, extraction) shouldBe true
                    usages(batchId.toString()) shouldBe 1
                    sql("SELECT entitlement_at_commit = 'UNLIMITED' FROM scan_usage") shouldBe 1
                }
            }
        }

        given("묶음 조회") {
            `when`("다른 회원이 묶음 id 로 조회하면") {
                then("404 SCAN-010") {
                    val owner = member("batch-read")
                    val batchId = newBatch(owner)
                    read(member("batch-intruder"), batchId, expected = 404).path("code").asText() shouldBe "SCAN-010"
                    read(owner, batchId).path("payload").path("batchId").asText() shouldBe batchId
                    read(owner, "not-a-uuid", expected = 404).path("code").asText() shouldNotBe ""
                }
            }
        }
    }
}
