package com.kbap.api.image

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ImageControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var storage: FakeStorageObjectStore

    @Autowired
    private lateinit var redisTemplate: org.springframework.data.redis.core.StringRedisTemplate

    @Autowired
    private lateinit var uploadedImageRepository: com.kbap.common.domain.image.UploadedImageJpaRepository

    init {
        val mapper = jacksonObjectMapper()

        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, "image-test-$memberId"); ps.executeUpdate() }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun countImage(path: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM uploaded_image WHERE object_path = ?").use { ps ->
                    ps.setString(1, path)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun body(path: String, contentType: String, size: Long) =
            mapper.writeValueAsString(mapOf("path" to path, "contentType" to contentType, "size" to size))

        fun resetGuestQuota() {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("DELETE FROM uploaded_image WHERE installation_id LIKE 'upload-quota-%'") }
            }
            redisTemplate.keys("upload:quota:*").takeIf { it.isNotEmpty() }?.let { redisTemplate.delete(it) }
        }

        fun guestPath(name: String) = "dev/images/feedback/2026/10/$name.webp"

        fun guestComplete(installation: String, path: String, declaredSize: Long = 1024): Pair<Int, String> {
            val response = mockMvc.post("/api/images/complete") {
                header("X-Installation-Id", installation)
                contentType = MediaType.APPLICATION_JSON
                content = body(path, "image/webp", declaredSize)
            }.andReturn().response
            return response.status to mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText()
        }

        fun guestRows(installation: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM uploaded_image WHERE installation_id = ?").use { ps ->
                    ps.setString(1, installation)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        given("게스트 업로드 한도 — 기기당 직전 24시간 10건, 완료 시점에 센다") {
            `when`("같은 기기에서 서로 다른 업로드 50건의 완료 신고가 한꺼번에 오면") {
                then("정확히 10건만 기록되고 나머지는 429 IMAGE-006 이다 — 세고 나서 넣는 사이로 한도를 넘지 않는다") {
                    resetGuestQuota()
                    storage.headDelayMillis = 30
                    try {
                    repeat(3) { round ->
                        val installation = "upload-quota-burst-$round"
                        val paths = (1..50).map { guestPath("burst-$round-$it") }
                        paths.forEach { storage.stub(it, "image/webp", 1024) }
                        val gate = java.util.concurrent.CountDownLatch(1)
                        val executor = java.util.concurrent.Executors.newFixedThreadPool(50)
                        val responses = paths.map { path -> executor.submit<Pair<Int, String>> { gate.await(); guestComplete(installation, path) } }
                        executor.shutdown()
                        gate.countDown()
                        val outcomes = responses.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }

                        outcomes.count { it.first == 200 } shouldBe GuestUploadQuota.DAILY_LIMIT
                        outcomes.filter { it.first != 200 }.toSet() shouldBe setOf(429 to "IMAGE-006")
                        guestRows(installation) shouldBe GuestUploadQuota.DAILY_LIMIT
                    }
                    } finally {
                        storage.headDelayMillis = 0
                    }
                }
            }

            `when`("카운터(Redis)가 비어 있는데 DB 엔 직전 24시간 업로드가 7건 있는 기기에서 10건이 한꺼번에 완료 신고하면") {
                then("남은 3건만 기록된다 — 빈 카운터가 한도 전체를 새로 내주지 않고 이미 기록된 건수에서 시작한다") {
                    resetGuestQuota()
                    val installation = "upload-quota-seeded"
                    val recordedAt = java.time.LocalDateTime.now().minusHours(1)
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO uploaded_image (installation_id, object_path, content_type, size_bytes, status, created_at, updated_at) " +
                                "VALUES (?, ?, 'image/webp', 1024, 'ACTIVE', ?, ?)",
                        ).use { ps ->
                            repeat(7) { i ->
                                ps.setString(1, installation); ps.setString(2, guestPath("seeded-old-$i"))
                                ps.setObject(3, recordedAt); ps.setObject(4, recordedAt); ps.addBatch()
                            }
                            ps.executeBatch()
                        }
                    }
                    val paths = (1..10).map { guestPath("seeded-new-$it") }
                    paths.forEach { storage.stub(it, "image/webp", 1024) }
                    storage.headDelayMillis = 30
                    try {
                        val gate = java.util.concurrent.CountDownLatch(1)
                        val executor = java.util.concurrent.Executors.newFixedThreadPool(10)
                        val responses = paths.map { path -> executor.submit<Pair<Int, String>> { gate.await(); guestComplete(installation, path) } }
                        executor.shutdown()
                        gate.countDown()
                        val outcomes = responses.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }

                        outcomes.count { it.first == 200 } shouldBe 3
                        outcomes.filter { it.first != 200 }.toSet() shouldBe setOf(429 to "IMAGE-006")
                        guestRows(installation) shouldBe GuestUploadQuota.DAILY_LIMIT
                    } finally {
                        storage.headDelayMillis = 0
                    }
                }
            }

            `when`("같은 업로드의 완료 신고를 여러 번 보내면") {
                then("한도를 한 번만 쓴다 — 재시도 3번 뒤에도 다른 업로드 9건이 모두 기록되고 11번째만 429 다") {
                    resetGuestQuota()
                    val installation = "upload-quota-idempotent"
                    val first = guestPath("idem-0").also { storage.stub(it, "image/webp", 1024) }

                    repeat(3) { guestComplete(installation, first).first shouldBe 200 }
                    (1..9).forEach { i ->
                        val path = guestPath("idem-$i").also { storage.stub(it, "image/webp", 1024) }
                        guestComplete(installation, path).first shouldBe 200
                    }

                    val eleventh = guestPath("idem-10").also { storage.stub(it, "image/webp", 1024) }
                    guestComplete(installation, eleventh) shouldBe (429 to "IMAGE-006")
                    guestRows(installation) shouldBe GuestUploadQuota.DAILY_LIMIT
                }
            }

            `when`("검증에 실패한 완료 신고가 한도만큼 있었으면") {
                then("한도를 쓰지 않는다 — 실패한 신고는 자리를 되돌려, 뒤의 정상 업로드 10건이 모두 기록된다") {
                    resetGuestQuota()
                    val installation = "upload-quota-release"
                    repeat(GuestUploadQuota.DAILY_LIMIT) { i ->
                        val path = guestPath("mismatch-$i").also { storage.stub(it, "image/webp", 1024) }
                        guestComplete(installation, path, declaredSize = 999).first shouldBe 400
                    }

                    repeat(GuestUploadQuota.DAILY_LIMIT) { i ->
                        val path = guestPath("valid-$i").also { storage.stub(it, "image/webp", 1024) }
                        guestComplete(installation, path).first shouldBe 200
                    }
                }
            }

            `when`("한도 카운터(Redis)를 쓸 수 없으면") {
                then("업로드를 막지 않고 DB 건수로 판정한다 — 10건까지 통과, 11번째는 IMAGE-006") {
                    resetGuestQuota()
                    val installation = "upload-quota-fallback"
                    val unavailable = object : com.kbap.common.port.quota.InstallationQuotaStore {
                        override fun tryAcquire(scope: String, installationId: String, requestId: String, limit: Int, window: java.time.Duration, recordedAtMillis: List<Long>): Boolean =
                            throw org.springframework.data.redis.RedisConnectionFailureException("테스트 — Redis 불가")

                        override fun release(scope: String, installationId: String, requestId: String) =
                            throw org.springframework.data.redis.RedisConnectionFailureException("테스트 — Redis 불가")
                    }
                    val service = ImageUploadService(storage, uploadedImageRepository, DailyGuestUploadQuota(uploadedImageRepository, unavailable))
                    fun complete(i: Int) {
                        val path = guestPath("fallback-$i").also { storage.stub(it, "image/webp", 1024) }
                        service.completeUpload(null, installation, path, "image/webp", 1024)
                    }

                    repeat(GuestUploadQuota.DAILY_LIMIT) { complete(it) }

                    io.kotest.assertions.throwables.shouldThrow<com.kbap.common.core.error.BusinessException> { complete(99) }
                        .errorCode shouldBe com.kbap.common.core.error.ErrorCode.IMAGE_UPLOAD_RATE_LIMITED
                }
            }
        }

        given("업로드 완료 신고 — POST /api/images/complete") {
            `when`("실제 오브젝트가 사진이고 신고값과 일치하면") {
                then("200 과 경로를 반환하고 이미지를 기록한다") {
                    val path = "scan/1/success.jpg"
                    storage.stub(path, "image/jpeg", 1048576)

                    mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${accessToken(1L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "image/jpeg", 1048576)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.path") { value(path) }
                    }

                    countImage(path) shouldBe 1
                }
            }

            `when`("실제 오브젝트가 이미지가 아니면(영상 등)") {
                then("400 IMAGE-001 로 거절하고 오브젝트를 삭제한다") {
                    val path = "scan/2/clip.mp4"
                    storage.stub(path, "video/mp4", 5048576)

                    mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${accessToken(2L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "video/mp4", 5048576)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-001") }
                    }

                    storage.deleted shouldContain path
                    countImage(path) shouldBe 0
                }
            }

            `when`("신고한 형식·크기가 실제와 다르면") {
                then("400 IMAGE-002 로 거절하고 오브젝트를 삭제한다") {
                    val path = "scan/3/mismatch.jpg"
                    storage.stub(path, "image/jpeg", 2048)

                    mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${accessToken(3L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "image/jpeg", 9999)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-002") }
                    }

                    storage.deleted shouldContain path
                    countImage(path) shouldBe 0
                }
            }

            `when`("해당 경로에 오브젝트가 없으면") {
                then("400 IMAGE-003 으로 거절한다") {
                    val path = "scan/4/missing.jpg"

                    mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${accessToken(4L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "image/jpeg", 1024)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-003") }
                    }
                }
            }

            `when`("같은 경로로 다시 신고하면") {
                then("재검증 없이 성공하고 기록은 1건이다(멱등)") {
                    val path = "scan/5/idem.jpg"
                    storage.stub(path, "image/png", 3000)

                    repeat(2) {
                        mockMvc.post("/api/images/complete") {
                            header("Authorization", "Bearer ${accessToken(5L)}")
                            contentType = MediaType.APPLICATION_JSON
                            content = body(path, "image/png", 3000)
                        }.andExpect { status { isOk() } }
                    }

                    storage.headCalls.count { it == path } shouldBe 1
                    countImage(path) shouldBe 1
                }
            }

            `when`("경로 대신 전체 URL 을 넘기면") {
                then("400 으로 거절한다(경로만 허용)") {
                    mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${accessToken(6L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("https://cdn.example.com/scan/6/x.jpg", "image/jpeg", 1024)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    mockMvc.post("/api/images/complete") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body("scan/7/x.jpg", "image/jpeg", 1024)
                    }.andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }
    }
}
