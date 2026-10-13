package com.kbap.api.reviewbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.api.image.ImageUploadProperties
import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.sql.DataSource
import kotlin.random.Random

@IntegrationTest
class ReviewBotPhotoTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var storage: FakeStorageObjectStore
    @Autowired private lateinit var uploadProperties: ImageUploadProperties
    @Autowired private lateinit var accountService: ReviewBotAccountService
    @Autowired private lateinit var reviewRepository: ReviewJpaRepository
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var ingredientRepository: IngredientJpaRepository
    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var fakeGenerator: FakeReviewTextGenerator
    @Autowired private lateinit var priorityList: ReviewBotPriorityList
    @Autowired private lateinit var photoPool: ReviewBotPhotoPool

    private val seoul = ZoneId.of("Asia/Seoul")
    private val mapper = jacksonObjectMapper()

    init {
        fun query(sql: String): List<List<Any?>> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val cols = rs.metaData.columnCount
                    generateSequence { if (rs.next()) (1..cols).map { rs.getObject(it) } else null }.toList()
                }
            }
        }
        fun count(sql: String): Long = (query(sql).single().single() as Number).toLong()
        fun poolPath(koreanName: String, n: Int) = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf(koreanName) + "/$n.webp"
        fun stubObjects(koreanName: String, count: Int = 3) = (1..count).forEach { storage.stub(poolPath(koreanName, it), "image/webp", 100_000L + it) }
        fun sync() = mockMvc.post("/api/admin/review-bots/images/sync") {
            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
        }.andReturn().response
        fun syncPayload() = mapper.readTree(sync().also { it.status shouldBe 200 }.contentAsString).path("payload")
        fun seedFood(koreanName: String): Food = foodRepository.save(Food(koreanName = koreanName, description = "설명", contentStatus = FoodContentStatus.READY))
        fun writer(min: Int = 1, max: Int = 1) = ReviewBotWriter(
            accountService, reviewRepository, foodRepository, ingredientRepository, reviewService, fakeGenerator, priorityList, photoPool, min, max, Duration.ofMinutes(15),
        )
        fun at(hour: Int, daysFromNow: Long = 0): ZonedDateTime = ZonedDateTime.now(seoul).plusDays(daysFromNow).withHour(hour).withMinute(0).withSecond(0).withNano(0)
        fun insertBotReview(botId: Long, foodId: Long, createdAt: LocalDateTime) {
            dataSource.connection.use { c ->
                c.prepareStatement("INSERT INTO food_review (member_id, food_id, rating, content, status, created_at, updated_at) VALUES (?, ?, 4, 'old bot review text', 'ACTIVE', ?, ?)")
                    .use { ps -> ps.setLong(1, botId); ps.setLong(2, foodId); ps.setObject(3, createdAt); ps.setObject(4, createdAt); ps.executeUpdate() }
            }
        }
        fun botReviewFoodIds(afterId: Long = 0): List<Long> =
            query("SELECT r.food_id FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.id > $afterId ORDER BY r.id").map { (it.single() as Number).toLong() }
        fun imageRefsOf(reviewId: Long): List<String> =
            mapper.readTree(query("SELECT image_refs FROM food_review WHERE id = $reviewId").single().single() as String).map { it.asText() }
        fun maxReviewId(): Long = count("SELECT COALESCE(MAX(id), 0) FROM food_review")

        beforeEach {
            TestTables.clearAll(dataSource)
            fakeGenerator.reset()
            storage.heads.clear()
            storage.lastModified.clear()
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("우선순위 목록") {
            `when`("리소스를 읽으면") {
                then("60개, 첫 항목 후라이드치킨, 중복 없음") {
                    priorityList.koreanNames.size shouldBe 60
                    priorityList.koreanNames.first() shouldBe "후라이드치킨"
                    priorityList.koreanNames.toSet().size shouldBe 60
                }
            }
        }

        given("사진 풀 등록 POST /api/admin/review-bots/images/sync") {
            `when`("S3 접두에 후라이드치킨 3장·비빔밥 2장·잡동사니 2개가 있으면") {
                then("풀 패턴에 맞는 5장만 봇 소유로 등록하고 키별 장수를 돌려준다 — 재실행은 전부 건너뛰고 행 중복 0") {
                    val bots = accountService.ensureBots(3).bots
                    stubObjects("후라이드치킨", 3)
                    stubObjects("비빔밥", 2)
                    storage.stub(ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + "6a2c1605affd/4.png", "image/png", 10)
                    storage.stub(ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + "notakey/1.webp", "image/webp", 10)

                    val first = syncPayload()

                    first.path("registeredCount").asInt() shouldBe 5
                    first.path("skippedCount").asInt() shouldBe 0
                    first.path("imagesPerKey").path("6a2c1605affd").asInt() shouldBe 3
                    first.path("imagesPerKey").path("574c26d16913").asInt() shouldBe 2
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%'") shouldBe 5
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%' AND member_id = ${bots.minOf { it.id }}") shouldBe 5

                    val second = syncPayload()

                    second.path("registeredCount").asInt() shouldBe 0
                    second.path("skippedCount").asInt() shouldBe 5
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%'") shouldBe 5
                }
            }

            `when`("봇 계정이 없으면") {
                then("400 — 풀 소유자가 없다") {
                    stubObjects("후라이드치킨", 3)
                    sync().status shouldBe 400
                    count("SELECT COUNT(*) FROM uploaded_image") shouldBe 0
                }
            }

            `when`("관리자 토큰이 없으면") {
                then("401") {
                    mockMvc.post("/api/admin/review-bots/images/sync").andReturn().response.status shouldBe 401
                }
            }
        }

        given("봇 리뷰의 사진 검증") {
            `when`("풀 경로가 아닌 사진(사람 회원의 리뷰 업로드)이나 빈 목록으로 봇 리뷰를 만들면") {
                then("REVIEW_IMAGE_NOT_VERIFIED — 풀(봇 소유·review-bot 세그먼트) 사진만 붙는다") {
                    val bots = accountService.ensureBots(2).bots
                    val human = accountService.ensureBots(3).bots.last()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET is_bot = 0 WHERE id = ${human.id}") } }
                    val food = seedFood("후라이드치킨")
                    val humanPath = "images/review/2026/10/${human.id}_a.webp"
                    val strayPath = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf("후라이드치킨") + "/9.webp"
                    dataSource.connection.use { c ->
                        c.prepareStatement("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES (?, ?, 'image/webp', 10, 'ACTIVE', NOW(6), NOW(6))").use { ps ->
                            ps.setLong(1, human.id); ps.setString(2, humanPath); ps.addBatch()
                            ps.setLong(1, human.id); ps.setString(2, strayPath); ps.addBatch()
                            ps.executeBatch()
                        }
                    }
                    val botIds = bots.map { it.id }.toSet()

                    shouldThrow<BusinessException> { reviewService.createBotReview(bots[0].id, food.id, 4, "The chicken was crisp and juicy, a real treat.", listOf(humanPath), botIds) }
                        .errorCode shouldBe ErrorCode.REVIEW_IMAGE_NOT_VERIFIED
                    shouldThrow<BusinessException> { reviewService.createBotReview(bots[0].id, food.id, 4, "The chicken was crisp and juicy, a real treat.", listOf(strayPath), botIds) }
                        .errorCode shouldBe ErrorCode.REVIEW_IMAGE_NOT_VERIFIED
                    shouldThrow<BusinessException> { reviewService.createBotReview(bots[0].id, food.id, 4, "The chicken was crisp and juicy, a real treat.", emptyList(), botIds) }
                        .errorCode shouldBe ErrorCode.REVIEW_IMAGE_NOT_VERIFIED
                    count("SELECT COUNT(*) FROM food_review") shouldBe 0
                }
            }
        }

        given("리뷰 봇 작성 — 우선순위와 사진") {
            `when`("목록 음식(후라이드치킨 2건·비빔밥 3건)과 일반 음식이 있을 때 이틀 연속 하루 1건씩 쓰면") {
                then("첫날은 후라이드치킨(3건 미만), 둘째 날은 목록이 소진돼 기존 순(리뷰 적은 일반 음식)으로 넘어간다") {
                    val bots = accountService.ensureBots(5).bots
                    val chicken = seedFood("후라이드치킨")
                    val bibimbap = seedFood("비빔밥")
                    val plain = seedFood("봇대상음식1")
                    listOf("후라이드치킨", "비빔밥", "봇대상음식1").forEach { stubObjects(it, 3) }
                    syncPayload().path("registeredCount").asInt() shouldBe 9
                    val yesterday = LocalDateTime.now().minusDays(1)
                    bots.take(2).forEach { insertBotReview(it.id, chicken.id, yesterday) }
                    bots.take(3).forEach { insertBotReview(it.id, bibimbap.id, yesterday) }
                    val seeded = maxReviewId()

                    writer().writeDue(at(22))
                    val afterDayOne = maxReviewId()
                    writer().writeDue(at(22, daysFromNow = 1))

                    botReviewFoodIds(seeded) shouldBe listOf(chicken.id, plain.id)
                    afterDayOne shouldNotBe seeded
                }
            }

            `when`("같은 음식에 이틀 연속 쓰면") {
                then("리뷰마다 풀의 1~3장이 붙고 둘째 날 조합은 첫날과 다르다") {
                    accountService.ensureBots(3)
                    val food = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 3)
                    syncPayload()
                    val pool = (1..3).map { poolPath("봇대상음식1", it) }.toSet()

                    writer().writeDue(at(22), Random(11))
                    val firstId = maxReviewId()
                    writer().writeDue(at(22, daysFromNow = 1), Random(11))
                    val secondId = maxReviewId()

                    secondId shouldNotBe firstId
                    val first = imageRefsOf(firstId)
                    val second = imageRefsOf(secondId)
                    first.size shouldBeInRange 1..3
                    second.size shouldBeInRange 1..3
                    pool.containsAll(first) shouldBe true
                    pool.containsAll(second) shouldBe true
                    first.toSet() shouldNotBe second.toSet()
                    count("SELECT COUNT(*) FROM food_review WHERE food_id = ${food.id}") shouldBe 2
                }
            }

            `when`("사진 풀이 없는 음식뿐이면") {
                then("목록 음식이라도 건너뛴다 — 사진 없는 봇 리뷰 0, 텍스트 생성 호출 0") {
                    accountService.ensureBots(3)
                    seedFood("후라이드치킨")
                    seedFood("봇대상음식1")

                    writer(min = 5, max = 5).writeDue(at(22))

                    count("SELECT COUNT(*) FROM food_review") shouldBe 0
                    fakeGenerator.requests.size shouldBe 0
                }
            }
        }
    }
}
