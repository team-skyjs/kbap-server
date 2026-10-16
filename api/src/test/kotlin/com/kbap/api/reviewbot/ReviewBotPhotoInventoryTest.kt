package com.kbap.api.reviewbot

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.api.image.ImageUploadProperties
import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class ReviewBotPhotoInventoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired private lateinit var catalog: ReviewBotPhotoCatalog
    @Autowired private lateinit var storage: FakeStorageObjectStore
    @Autowired private lateinit var accounts: ReviewBotAccountService
    @Autowired private lateinit var properties: ImageUploadProperties
    @Autowired private lateinit var images: UploadedImageJpaRepository
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var foods: FoodJpaRepository
    @Autowired private lateinit var reviews: ReviewJpaRepository
    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var dataSource: DataSource

    init {
        fun service(enabled: Boolean = true) = ReviewBotPhotoService(catalog, storage, accounts, properties, enabled)
        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun food() = foods.save(Food(koreanName = "사진검증음식", description = "설명", contentStatus = FoodContentStatus.READY))
        fun path(food: Food, n: Int = 1) = service().prefix + ReviewBotPhotoService.keyOf(food.koreanName) + "/$n.webp"
        fun ordinary() = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "photo-ordinary", null)))
        beforeEach {
            TestTables.clearAll(dataSource)
            storage.heads.clear()
            storage.lastModified.clear()
            storage.deleted.clear()
            storage.headCalls.clear()
            storage.headDelayMillis = 0
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("소실 사진 보류와 기존 리뷰 보존") {
            `when`("등록해 사용하던 사진이 저장소에서 사라지면") {
                then("새 작성 풀에서 제외하고 등록행과 기존 리뷰를 유지한다") {
                    val bot = accounts.ensureBots(1).bots.single()
                    val food = food()
                    val path = path(food)
                    storage.stub(path, "image/webp", 512)
                    service().sync().registeredCount shouldBe 1
                    val review = reviewService.createBotReview(bot.id, food.id, 5, "사진이 포함된 리뷰입니다", listOf(path))
                    storage.heads.remove(path)
                    service().getAvailablePaths(food.koreanName) shouldBe emptyList()
                    service().sync().held.single().reason shouldBe "MISSING_OBJECT"
                    reviews.findById(review.reviewId).orElseThrow().imageRefs shouldBe listOf(path)
                    images.findByPath(path)!!.memberId shouldBe bot.id
                    storage.deleted shouldBe emptyList()
                }
            }
        }
        given("기존 일반 참조와 소유 충돌") {
            `when`("봇 소유 사진을 일반 리뷰가 참조하거나 DELETED 또는 일반 소유이면") {
                then("등록 복원과 소유 변경 및 리뷰 삭제 없이 보류한다") {
                    val bot = accounts.ensureBots(1).bots.single()
                    val normal = ordinary()
                    val food = food()
                    val paths = (1..3).map { path(food, it) }
                    paths.forEach { storage.stub(it, "image/webp", 512) }
                    images.save(UploadedImage(memberId = bot.id, path = paths[0], contentType = "image/webp", sizeBytes = 512))
                    reviews.save(Review(memberId = normal.id, foodId = food.id, rating = 5, imageRefs = listOf(paths[0])))
                    images.save(UploadedImage(memberId = normal.id, path = paths[1], contentType = "image/webp", sizeBytes = 512))
                    images.save(UploadedImage(memberId = bot.id, path = paths[2], contentType = "image/webp", sizeBytes = 512))
                    exec("UPDATE uploaded_image SET status='DELETED' WHERE object_path='${paths[2]}'")
                    val result = service().sync()
                    result.registeredCount shouldBe 0
                    result.held.map { it.reason }.sorted() shouldBe listOf("ORDINARY_REFERENCE", "OWNER_OR_STATUS", "OWNER_OR_STATUS")
                    reviews.count() shouldBe 1
                    images.findByPath(paths[1])!!.memberId shouldBe normal.id
                    images.findByPath(paths[2]) shouldBe null
                    storage.deleted shouldBe emptyList()
                }
            }
        }
        given("일반 게시글과 문자열 사진 참조") {
            `when`("저장소 경로 중 일부를 커뮤니티와 회원 프로필이 참조하면") {
                then("각 경로의 참조만 찾아 보류한다") {
                    val normal = ordinary()
                    val food = food()
                    val paths = (1..3).map { path(food, it) }
                    exec("INSERT INTO community_post(member_id, content, image_refs, status, created_at, updated_at) VALUES(${normal.id}, '사진', JSON_ARRAY('${paths[0]}'), 'ACTIVE', NOW(6), NOW(6))")
                    exec("UPDATE member SET profile_image_url='https://example.invalid/${paths[1]}' WHERE id=${normal.id}")
                    catalog.getOrdinaryReferencedPaths(paths).toSet() shouldBe paths.take(2).toSet()
                }
            }
        }
        given("기존 일반 리뷰 삭제 의미") {
            `when`("회원 리뷰 카운트가 이미 영이면") {
                then("기존 예외와 트랜잭션 롤백을 유지한다") {
                    val normal = ordinary()
                    val food = food()
                    val review = reviewService.createReview(normal.id, food.id, 5, null, null, "일반 리뷰입니다", null, null)
                    exec("UPDATE member SET review_count=0 WHERE id=${normal.id}")
                    shouldThrow<BusinessException> { reviewService.deleteReview(normal.id, review.reviewId) }
                    reviews.findById(review.reviewId).isPresent shouldBe true
                    foods.findById(food.id).orElseThrow().reviewCount shouldBe 1
                }
            }
        }
    }
}
