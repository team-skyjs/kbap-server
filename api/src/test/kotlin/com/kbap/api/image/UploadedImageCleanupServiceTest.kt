package com.kbap.api.image

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.image.UploadedImageJpaRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@IntegrationTest
class UploadedImageCleanupServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var uploadedImageRepository: UploadedImageJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val memberId = 7101L
        val foodId = 7101L
        val storage = FakeStorageObjectStore()

        fun cleanupService(dryRun: Boolean = false, pageSize: Int = 100) =
            UploadedImageCleanupService(uploadedImageRepository, storage, 7, dryRun, pageSize, transactionManager)

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun reset() {
            TestTables.clearAll(dataSource)
            storage.deleted.clear()
            exec(
                "INSERT INTO member (id, provider, provider_uid, country_code, member_status, onboarding_completed, " +
                    "status, created_at, updated_at) VALUES ($memberId, 'GOOGLE', 'cleanup-test', 'KR', 'ACTIVE', 1, " +
                    "'ACTIVE', NOW(6), NOW(6))",
            )
            exec(
                "INSERT INTO food (id, korean_name, display_name, description, spiciness, name_translations, " +
                    "description_translations, ingredients, content_status, status, created_at, updated_at) VALUES " +
                    "($foodId, '정리음식', '정리음식', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
        }

        fun upload(path: String, daysAgo: Int = 10): Long {
            exec(
                "INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, " +
                    "updated_at) VALUES ($memberId, '$path', 'image/webp', 1, 'ACTIVE', " +
                    "NOW(6) - INTERVAL $daysAgo DAY, NOW(6) - INTERVAL $daysAgo DAY)",
            )
            return uploadedImageRepository.findByPath(path)!!.id
        }

        fun review(ref: String, status: String = "ACTIVE") = exec(
            "INSERT INTO food_review (member_id, food_id, rating, image_refs, status) " +
                "VALUES ($memberId, $foodId, 5, JSON_ARRAY('$ref'), '$status')",
        )

        fun post(ref: String) = exec(
            "INSERT INTO community_post (member_id, content, image_refs, status, created_at, updated_at) " +
                "VALUES ($memberId, '글', JSON_ARRAY('$ref'), 'ACTIVE', NOW(6), NOW(6))",
        )

        fun feedback(ref: String) = exec(
            "INSERT INTO feedback (member_id, installation_id, content, image_refs, created_at, updated_at) " +
                "VALUES ($memberId, 'inst-cleanup', '문의', JSON_ARRAY('$ref'), NOW(6), NOW(6))",
        )

        fun activePaths(): Set<String> = uploadedImageRepository.findAll().map { it.path }.toSet()

        given("미참조 업로드 정리") {
            `when`("리뷰·커뮤니티·문의 용도의 업로드가 보존 기간을 넘겼고 어디에도 참조되지 않으면") {
                then("행을 DELETED 로 바꾸고 S3 오브젝트를 지운다") {
                    reset()
                    val paths = listOf(
                        "dev/images/review/a.webp",
                        "dev/images/community/b.webp",
                        "dev/images/feedback/c.webp",
                    )
                    paths.forEach { upload(it) }

                    val result = cleanupService().cleanup()

                    result.deletedCount shouldBe 3
                    activePaths().shouldBeEmpty()
                    storage.deleted shouldContainExactlyInAnyOrder paths
                }
            }

            `when`("다섯 용도 각각이 참조되고 있으면") {
                then("하나도 지우지 않는다") {
                    reset()
                    val reviewPath = "dev/images/review/used.webp"
                    val postPath = "dev/images/community/used.webp"
                    val feedbackPath = "dev/images/feedback/used.webp"
                    val profilePath = "dev/images/profile/used.webp"
                    val scanPath = "dev/images/scans/used.webp"
                    listOf(reviewPath, postPath, feedbackPath, profilePath, scanPath).forEach { upload(it) }
                    review(reviewPath)
                    post(postPath)
                    feedback(feedbackPath)
                    exec("UPDATE member SET profile_image_url = '$profilePath' WHERE id = $memberId")
                    exec("INSERT INTO orders (member_id, image_path) VALUES ($memberId, '$scanPath')")

                    cleanupService().cleanup().deletedCount shouldBe 0

                    activePaths() shouldBe setOf(reviewPath, postPath, feedbackPath, profilePath, scanPath)
                    storage.deleted.shouldBeEmpty()
                }
            }

            `when`("프로필·스캔·목록에 없는 용도의 업로드는 참조가 없어도") {
                then("허용 목록 밖이라 지우지 않는다 — 모르는 용도의 기본값은 남긴다") {
                    reset()
                    val kept = setOf(
                        "dev/images/profile/old.webp",
                        "dev/images/scans/menu.webp",
                        "dev/images/unknown/new-purpose.webp",
                    )
                    kept.forEach { upload(it) }

                    cleanupService().cleanup().deletedCount shouldBe 0

                    activePaths() shouldBe kept
                }
            }

            `when`("소프트 삭제된 리뷰가 참조하고 있으면") {
                then("지우지 않는다 — 복원될 수 있는 글의 사진이다") {
                    reset()
                    val path = "dev/images/review/deleted-review.webp"
                    upload(path)
                    review(path, status = "DELETED")

                    cleanupService().cleanup().deletedCount shouldBe 0
                }
            }

            `when`("도메인이 붙은 형태로 참조되고 있으면") {
                then("접미 일치로 참조로 보고 지우지 않는다") {
                    reset()
                    val path = "dev/images/review/with-domain.webp"
                    upload(path)
                    review("https://cdn.example.com/$path")

                    cleanupService().cleanup().deletedCount shouldBe 0
                }
            }

            `when`("보존 기간 안의 업로드는") {
                then("참조가 없어도 지우지 않는다 — 작성 중인 글의 사진일 수 있다") {
                    reset()
                    upload("dev/images/review/fresh.webp", daysAgo = 1)

                    cleanupService().cleanup().deletedCount shouldBe 0
                }
            }

            `when`("dry-run 이면") {
                then("용도별 건수만 세고 행도 오브젝트도 그대로 둔다") {
                    reset()
                    upload("dev/images/review/r1.webp")
                    upload("dev/images/review/r2.webp")
                    upload("dev/images/feedback/f1.webp")

                    val result = cleanupService(dryRun = true).cleanup()

                    result.dryRun shouldBe true
                    result.deletedCount shouldBe 0
                    result.orphanCounts shouldBe mapOf("review" to 2L, "community" to 0L, "feedback" to 1L)
                    activePaths().size shouldBe 3
                    storage.deleted.shouldBeEmpty()
                }
            }

            `when`("대상이 페이지 크기보다 많으면") {
                then("페이지를 넘기며 전부 지운다") {
                    reset()
                    (1..5).forEach { upload("dev/images/community/p$it.webp") }

                    cleanupService(pageSize = 2).cleanup().deletedCount shouldBe 5

                    activePaths().shouldBeEmpty()
                }
            }
        }

        given("정리 대상 용도 목록") {
            `when`("건수 집계용 목록과 판정 쿼리를 비교하면") {
                then("목록의 모든 용도가 판정 쿼리의 허용 목록에 있다 — 둘이 어긋나면 집계와 삭제가 다른 대상을 본다") {
                    UploadedImageJpaRepository.CLEANUP_SEGMENTS.forEach { UploadedImageJpaRepository.ORPHAN shouldContain "'$it'" }
                    Regex("locate\\('").findAll(UploadedImageJpaRepository.ORPHAN).count() shouldBe
                        UploadedImageJpaRepository.CLEANUP_SEGMENTS.size
                }
            }
        }
    }
}
