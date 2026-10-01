package com.kbap.api.image

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.port.storage.PresignedUpload
import com.kbap.common.port.storage.PresignedUploadPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

@IntegrationTest
class UnrecordedUploadCleanupServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var uploadedImageRepository: UploadedImageJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var redisTemplate: org.springframework.data.redis.core.StringRedisTemplate

    init {
        val memberId = 7201L
        val storage = FakeStorageObjectStore()

        fun properties(keyPrefix: String = "local", uploadTtl: Duration = Duration.ofMinutes(5)) = ImageUploadProperties(
            allowedContentTypes = setOf("image/jpeg", "image/png"),
            maxBytes = 1_000L,
            uploadTtl = uploadTtl,
            publicBaseUrl = "https://cdn.test",
            keyPrefix = keyPrefix,
            completeWindow = Duration.ofDays(1),
        )

        fun service(
            dryRun: Boolean = false,
            retentionDays: Long = 7,
            maxDeletes: Int = 100,
            maxListed: Int = 20_000,
            repository: UploadedImageJpaRepository = uploadedImageRepository,
            meters: SimpleMeterRegistry = SimpleMeterRegistry(),
        ) = UnrecordedUploadCleanupService(
            repository,
            storage,
            redisTemplate,
            properties(),
            dryRun,
            retentionDays,
            maxDeletes,
            maxListed,
            UploadCleanupMetrics(meters),
        )

        fun gauge(meters: SimpleMeterRegistry, purpose: String, kind: String): Double? =
            meters.find(UploadCleanupMetrics.NAME).tags("cleanup", "unrecorded", "purpose", purpose, "kind", kind).gauge()?.value()

        fun repositoryThatRecordsAfterPageCheck(path: String): UploadedImageJpaRepository =
            java.lang.reflect.Proxy.newProxyInstance(
                UploadedImageJpaRepository::class.java.classLoader,
                arrayOf(UploadedImageJpaRepository::class.java),
            ) { _, method, args ->
                val result = method.invoke(uploadedImageRepository, *(args ?: emptyArray()))
                if (method.name == "findRecordedPathsAnyStatus") {
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) " +
                                "VALUES ($memberId, ?, 'image/webp', 1, 'ACTIVE', NOW(6), NOW(6))",
                        ).use { ps -> ps.setString(1, path); ps.executeUpdate() }
                    }
                }
                result
            } as UploadedImageJpaRepository

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        val old: Instant = Instant.now().minus(Duration.ofDays(10))

        fun reset() {
            TestTables.clearAll(dataSource)
            storage.heads.clear()
            storage.lastModified.clear()
            storage.deleted.clear()
            storage.failDeletes = false
            storage.failDeletePaths.clear()
            redisTemplate.delete(UnrecordedUploadCleanupService.CURSOR_KEY)
            exec(
                "INSERT INTO member (id, provider, provider_uid, country_code, member_status, onboarding_completed, " +
                    "status, created_at, updated_at) VALUES ($memberId, 'GOOGLE', 'unrecorded-test', 'KR', 'ACTIVE', 1, " +
                    "'ACTIVE', NOW(6), NOW(6))",
            )
        }

        fun stored(path: String, modifiedAt: Instant = old) = storage.stub(path, "image/webp", 1, modifiedAt)

        fun recorded(path: String, status: String = "ACTIVE") = exec(
            "INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) " +
                "VALUES ($memberId, '$path', 'image/webp', 1, '$status', NOW(6), NOW(6))",
        )

        given("행 없는 업로드 오브젝트 정리") {
            `when`("업로드 접두 아래에 행 없는 오래된 오브젝트, 행 없는 새 오브젝트, 행 있는 오브젝트, 소프트 삭제된 행의 오브젝트가 섞여 있으면") {
                then("행 없이 보존 기간을 지난 것만 지운다 — 행이 있으면(DELETED 포함) 이 잡의 몫이 아니다") {
                    reset()
                    val staleUnrecorded = "local/images/review/2026/09/1_stale.webp"
                    val freshUnrecorded = "local/images/review/2026/10/1_fresh.webp"
                    val active = "local/images/community/2026/09/1_active.webp"
                    val softDeleted = "local/images/feedback/2026/09/1_deleted.webp"
                    stored(staleUnrecorded)
                    stored(freshUnrecorded, modifiedAt = Instant.now().minus(Duration.ofDays(2)))
                    stored(active)
                    stored(softDeleted)
                    recorded(active)
                    recorded(softDeleted, status = "DELETED")

                    val result = service().cleanup()

                    storage.deleted shouldContainExactlyInAnyOrder listOf(staleUnrecorded)
                    result.deletedCount shouldBe 1
                    result.skippedCount shouldBe 0
                    result.counts.getValue("review") shouldBe UnrecordedUploadCount(listed = 2, unrecorded = 2, stale = 1)
                    result.counts.getValue("community") shouldBe UnrecordedUploadCount(listed = 1, unrecorded = 0, stale = 0)
                    result.counts.getValue("feedback") shouldBe UnrecordedUploadCount(listed = 1, unrecorded = 0, stale = 0)
                }
            }

            `when`("행 없이 보존 기간을 지났지만 회원 프로필·리뷰·게시글·문의·주문·주문 항목 중 하나가 그 키를 가리키면") {
                then("지우지 않는다 — #293 정리와 같은 참조 판정으로 '모르면 남긴다'. dry-run 집계엔 '경과했지만 참조됨'으로 센다") {
                    reset()
                    exec(
                        "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, ingredients, " +
                            "content_status, status, created_at, updated_at) VALUES (7202, '참조음식', '설명', 0, '{}', '{}', '[]', 'READY', " +
                            "'ACTIVE', NOW(6), NOW(6))",
                    )
                    val profile = "local/images/profile/2026/08/${memberId}_legacy.webp"
                    val review = "local/images/review/2026/07/1_legacy.webp"
                    val post = "local/images/community/2026/07/1_legacy.webp"
                    val feedback = "local/images/feedback/2026/07/1_legacy.webp"
                    val scan = "local/images/scans/2026/07/1_legacy.webp"
                    val item = "local/images/orders/2026/07/1_legacy.webp"
                    val orphan = "local/images/review/2026/07/1_orphan.webp"
                    listOf(profile, review, post, feedback, scan, item, orphan).forEach { stored(it) }
                    exec("UPDATE member SET profile_image_url = '$profile' WHERE id = $memberId")
                    exec(
                        "INSERT INTO food_review (member_id, food_id, rating, image_refs, status) " +
                            "VALUES ($memberId, 7202, 5, JSON_ARRAY('https://cdn.test/$review'), 'ACTIVE')",
                    )
                    exec(
                        "INSERT INTO community_post (member_id, content, image_refs, status, created_at, updated_at) " +
                            "VALUES ($memberId, '글', JSON_ARRAY('$post'), 'ACTIVE', NOW(6), NOW(6))",
                    )
                    exec(
                        "INSERT INTO feedback (member_id, installation_id, content, image_refs, created_at, updated_at) " +
                            "VALUES ($memberId, 'inst-unrecorded', '문의', JSON_ARRAY('$feedback'), NOW(6), NOW(6))",
                    )
                    exec("INSERT INTO orders (id, member_id, image_path) VALUES (7202, $memberId, '$scan')")
                    exec("INSERT INTO order_item (order_id, food_id, menu_name, quantity, image_path) VALUES (7202, 7202, '참조음식', 1, '$item')")

                    val dry = service(dryRun = true).cleanup()
                    dry.counts.values.sumOf { it.staleReferenced } shouldBe 6
                    dry.counts.values.sumOf { it.stale } shouldBe 1

                    val result = service().cleanup()

                    storage.deleted shouldContainExactlyInAnyOrder listOf(orphan)
                    result.deletedCount shouldBe 1
                }
            }

            `when`("삭제 스위치를 볼 때") {
                then("#293 정리의 스위치와 따로다 — 이 잡만 실삭제로 켤 수 있고, 기본은 dry-run 이다") {
                    val field = UnrecordedUploadCleanupService::class.java.declaredConstructors.single().parameters
                        .mapNotNull { it.getAnnotation(org.springframework.beans.factory.annotation.Value::class.java)?.value }
                        .single { it.contains("dry-run") }
                    field shouldBe "\${kbap.uploaded-image-cleanup.unrecorded-dry-run:true}"
                }
            }

            `when`("업로드 접두 밖(카탈로그·기본 이미지·다른 환경)에 행 없는 오래된 오브젝트가 있으면") {
                then("목록에 오르지도 않고 지워지지도 않는다") {
                    reset()
                    listOf(
                        "local/images/webp/food/1.webp",
                        "images/webp/food/1.webp",
                        "images/default/profile/profile-default-512.png",
                        "prod/images/review/2026/09/1_other-env.webp",
                        "local/images/profile-old/1.webp",
                    ).forEach { stored(it) }

                    val result = service().cleanup()

                    storage.deleted.shouldBeEmpty()
                    result.counts.values.sumOf { it.listed } shouldBe 0
                }
            }

            `when`("dry-run 이면") {
                then("용도별 건수만 세고 아무것도 지우지 않는다") {
                    reset()
                    stored("local/images/scans/2026/09/1_a.webp")
                    stored("local/images/orders/2026/09/1_b.webp")

                    val result = service(dryRun = true).cleanup()

                    storage.deleted.shouldBeEmpty()
                    result.dryRun shouldBe true
                    result.counts.getValue("scans") shouldBe UnrecordedUploadCount(1, 1, 1)
                    result.counts.getValue("orders") shouldBe UnrecordedUploadCount(1, 1, 1)
                }
            }

            `when`("dry-run 실행을 메트릭으로 보면") {
                then("용도별 목록·행 없음·후보·참조돼 남김 건수와 마지막 실행 시각이 게이지로 남는다") {
                    reset()
                    stored("local/images/scans/2026/09/1_a.webp")
                    stored("local/images/orders/2026/09/1_b.webp")
                    stored("local/images/orders/2026/09/1_c.webp")
                    stored("local/images/profile/2026/09/${memberId}_d.webp")
                    exec("UPDATE member SET profile_image_url = 'local/images/profile/2026/09/${memberId}_d.webp' WHERE id = $memberId")
                    val meters = SimpleMeterRegistry()

                    service(dryRun = true, meters = meters).cleanup()

                    gauge(meters, "scans", "candidate") shouldBe 1.0
                    gauge(meters, "orders", "listed") shouldBe 2.0
                    gauge(meters, "orders", "unrecorded") shouldBe 2.0
                    gauge(meters, "orders", "candidate") shouldBe 2.0
                    gauge(meters, "profile", "kept_referenced") shouldBe 1.0
                    gauge(meters, "all", "deleted") shouldBe 0.0
                    meters.find(UploadCleanupMetrics.LAST_RUN).tags("cleanup", "unrecorded").gauge()!!.value() shouldBeGreaterThan 0.0
                }
            }

            `when`("한 페이지 안의 대상이 실행당 삭제 상한보다 많으면") {
                then("상한까지만 지우고 커서를 마지막으로 처리한 키에 둬, 다음 실행이 같은 페이지의 남은 대상부터 이어 간다") {
                    reset()
                    val keys = (1..5).map { "local/images/review/2026/09/1_cap$it.webp" }
                    keys.forEach { stored(it) }

                    service(maxDeletes = 2).cleanup().deletedCount shouldBe 2
                    storage.deleted shouldContainExactlyInAnyOrder keys.take(2)
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe "review|${keys[1]}"

                    service(maxDeletes = 2).cleanup().deletedCount shouldBe 2
                    storage.deleted shouldContainExactlyInAnyOrder keys.take(4)

                    service(maxDeletes = 2).cleanup().deletedCount shouldBe 1
                    storage.heads.size shouldBe 0
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe null
                }
            }

            `when`("dry-run 에서 한 페이지 안의 대상이 삭제 상한보다 많으면") {
                then("같은 규칙으로 상한까지만 세고 커서를 마지막으로 센 키에 둔다 — dry-run 집계가 실삭제 실행과 같은 범위를 본다") {
                    reset()
                    val keys = (1..3).map { "local/images/review/2026/09/1_dry$it.webp" }
                    keys.forEach { stored(it) }

                    val result = service(dryRun = true, maxDeletes = 2).cleanup()

                    result.counts.getValue("review").stale shouldBe 2
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe "review|${keys[1]}"
                }
            }

            `when`("페이지 단위 행 대조를 지난 뒤 삭제 직전에 완료 신고가 그 경로의 행을 만들면") {
                then("지우지 않고 건너뛴다 — 삭제 직전에 행을 다시 확인한다") {
                    reset()
                    val path = "local/images/review/2026/09/1_racing.webp"
                    stored(path)

                    val result = service(repository = repositoryThatRecordsAfterPageCheck(path)).cleanup()

                    storage.deleted.shouldBeEmpty()
                    result.skippedCount shouldBe 1
                    result.deletedCount shouldBe 0
                }
            }

            `when`("한 용도의 키가 실행당 목록 상한보다 많으면") {
                then("다음 실행이 지난 실행이 확정 처리한 마지막 키 뒤부터 이어 보고(행 있는 키도 확정), 그 용도를 다 보면 다음 용도로 넘어가며, 한 바퀴를 돌면 커서를 지운다") {
                    reset()
                    val scans = (1..3).map { "local/images/scans/2026/09/1_s$it.webp" }
                    val review = "local/images/review/2026/09/1_r1.webp"
                    (scans + review).forEach { stored(it) }
                    recorded(scans[1])

                    service(maxListed = 2).cleanup().deletedCount shouldBe 1
                    storage.deleted shouldContainExactlyInAnyOrder listOf(scans[0])
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe "scans|${scans[1]}"

                    service(maxListed = 2).cleanup().deletedCount shouldBe 2
                    storage.deleted shouldContainExactlyInAnyOrder listOf(scans[0], scans[2], review)
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe "review|$review"

                    service(maxListed = 2).cleanup().deletedCount shouldBe 0
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe null
                }
            }

            `when`("커서 저장소(Redis)가 없으면") {
                then("처음부터 훑는다 — 느려질 뿐 오판은 없다") {
                    reset()
                    val path = "local/images/review/2026/09/1_no-redis.webp"
                    stored(path)
                    val unreachable = org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        org.springframework.data.redis.connection.RedisStandaloneConfiguration("localhost", 1),
                        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                            .commandTimeout(Duration.ofMillis(300)).build(),
                    ).apply { afterPropertiesSet() }
                    try {
                        val broken = org.springframework.data.redis.core.StringRedisTemplate(unreachable)

                        val result = UnrecordedUploadCleanupService(uploadedImageRepository, storage, broken, properties(), false, 7, 100, 20_000, UploadCleanupMetrics(SimpleMeterRegistry())).cleanup()

                        result.deletedCount shouldBe 1
                    } finally {
                        unreachable.destroy()
                    }
                }
            }

            `when`("스토리지 삭제가 실패하면") {
                then("그 키 직전을 커서로 저장하고 이번 실행을 끝낸다 — 뒤의 키는 손대지 않고, 다음 실행이 실패한 키부터 다시 시도한다") {
                    reset()
                    val ok = "local/images/review/2026/09/1_a-ok.webp"
                    val failing = "local/images/review/2026/09/1_b-fail.webp"
                    val after = "local/images/review/2026/09/1_c-after.webp"
                    listOf(ok, failing, after).forEach { stored(it) }
                    storage.failDeletePaths += failing

                    val first = service().cleanup()

                    first.deletedCount shouldBe 1
                    first.failedCount shouldBe 1
                    storage.deleted shouldContainExactlyInAnyOrder listOf(ok)
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe "review|$ok|$failing|1"

                    storage.failDeletePaths.clear()
                    val second = service().cleanup()

                    second.deletedCount shouldBe 2
                    storage.deleted shouldContainExactlyInAnyOrder listOf(ok, failing, after)
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe null
                }
            }

            `when`("같은 키가 연속 3번 실행에서 삭제에 실패하면") {
                then("네 번째 실행이 그 키를 건너뛰고 뒤를 계속 처리한다 — 영구 실패 키가 앞을 막아 굶기지 않는다") {
                    reset()
                    val stuck = "local/images/review/2026/09/1_a-stuck.webp"
                    val after = "local/images/review/2026/09/1_b-after.webp"
                    listOf(stuck, after).forEach { stored(it) }
                    storage.failDeletePaths += stuck

                    repeat(UnrecordedUploadCleanupService.MAX_CONSECUTIVE_FAILURES) { service().cleanup().failedCount shouldBe 1 }
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe
                        "review||$stuck|${UnrecordedUploadCleanupService.MAX_CONSECUTIVE_FAILURES}"
                    storage.deleted.shouldBeEmpty()

                    val result = service().cleanup()

                    result.deletedCount shouldBe 1
                    result.failedCount shouldBe 0
                    result.skippedStuckCount shouldBe 1
                    storage.deleted shouldContainExactlyInAnyOrder listOf(after)
                    redisTemplate.opsForValue().get(UnrecordedUploadCleanupService.CURSOR_KEY) shouldBe null
                }
            }
        }

        given("보존 기간 설정") {
            `when`("보존 기간이 presigned 유효기간 + 완료 신고 여유보다 길지 않으면") {
                then("기동하지 않는다 — 그 안에는 새 PUT·완료 신고가 올 수 있어 행 없음이 '버려짐'을 뜻하지 않는다") {
                    shouldThrow<IllegalStateException> {
                        UnrecordedUploadCleanupService(uploadedImageRepository, storage, redisTemplate, properties(uploadTtl = Duration.ofMinutes(5)), true, 1, 100, 100, UploadCleanupMetrics(SimpleMeterRegistry()))
                    }
                    UnrecordedUploadCleanupService(uploadedImageRepository, storage, redisTemplate, properties(uploadTtl = Duration.ofMinutes(5)), true, 2, 100, 100, UploadCleanupMetrics(SimpleMeterRegistry()))
                }
            }
        }

        given("대상 접두 목록") {
            `when`("환경 접두와 업로드 용도로 목록을 만들면") {
                then("업로드 6용도 접두만 있고, 카탈로그·기본 이미지 접두는 없다") {
                    val prefixes = UnrecordedUploadCleanupService.uploadPrefixes("dev")

                    prefixes.keys shouldContainExactlyInAnyOrder UploadPurpose.entries
                    prefixes.values shouldContainExactlyInAnyOrder listOf(
                        "dev/images/scans/", "dev/images/review/", "dev/images/profile/",
                        "dev/images/community/", "dev/images/feedback/", "dev/images/orders/",
                    )
                    prefixes.values.none { it.contains("images/webp/") || it.contains("images/default/") } shouldBe true
                    UnrecordedUploadCleanupService.uploadPrefixes("").values.first() shouldBe "images/scans/"
                }
            }

            `when`("발급 규칙(PresignedUploadService)으로 만든 키를 목록 접두와 대조하면") {
                then("모든 용도의 발급 키가 그 용도의 접두로 시작한다 — 목록 키와 uploaded_image.object_path 의 형식이 발급 규칙과 같다") {
                    val issued = mutableListOf<String>()
                    val port = object : PresignedUploadPort {
                        override fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload {
                            issued += key
                            return PresignedUpload("https://s3/$key", emptyMap(), "https://cdn.test/$key", key, Instant.EPOCH)
                        }
                    }
                    val presign = PresignedUploadService(properties(keyPrefix = "dev"), port, GuestUploadQuota { it.orEmpty() })
                    val prefixes = UnrecordedUploadCleanupService.uploadPrefixes("dev")

                    UploadPurpose.entries.forEach { purpose ->
                        presign.issueUploadUrl(ImageUploadInput(9L, null, purpose.name, "image/jpeg", 100))
                        issued.last().startsWith(prefixes.getValue(purpose)) shouldBe true
                    }
                }
            }
        }
    }
}
