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
    @Autowired private lateinit var uploadedImageRepository: com.kbap.common.domain.image.UploadedImageJpaRepository
    @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

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
            storage.deleted.clear()
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

            `when`("S3 에서 지워진 사진이 있으면") {
                then("다음 sync 가 그 행을 소프트 삭제하고 removedCount 로 알린다 — 봇 리뷰에 죽은 경로가 붙지 않는다") {
                    accountService.ensureBots(2)
                    stubObjects("후라이드치킨", 3)
                    syncPayload().path("registeredCount").asInt() shouldBe 3
                    storage.delete(poolPath("후라이드치킨", 2))

                    val second = syncPayload()

                    second.path("removedCount").asInt() shouldBe 1
                    second.path("removalSkipped").asBoolean() shouldBe false
                    second.path("registeredCount").asInt() shouldBe 0
                    second.path("skippedCount").asInt() shouldBe 2
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%' AND status = 'ACTIVE'") shouldBe 2
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path = '${poolPath("후라이드치킨", 2)}' AND status = 'DELETED'") shouldBe 1
                }
            }

            `when`("지워졌던 경로에 사진을 다시 올리면") {
                then("다음 sync 가 DELETED 행을 되살려 풀로 돌아온다(restoredCount) — 행 중복 없이") {
                    accountService.ensureBots(2)
                    stubObjects("후라이드치킨", 3)
                    syncPayload()
                    storage.delete(poolPath("후라이드치킨", 2))
                    syncPayload().path("removedCount").asInt() shouldBe 1
                    storage.stub(poolPath("후라이드치킨", 2), "image/webp", 100_002L)

                    val third = syncPayload()

                    third.path("restoredCount").asInt() shouldBe 1
                    third.path("registeredCount").asInt() shouldBe 0
                    third.path("skippedCount").asInt() shouldBe 2
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%'") shouldBe 3
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%' AND status = 'ACTIVE'") shouldBe 3
                }
            }

            `when`("스토리지가 접두에 빈 목록을 돌려주면") {
                then("삭제 단계를 건너뛴다(removalSkipped) — 일시 장애로 풀 전체를 지우지 않는다") {
                    accountService.ensureBots(2)
                    stubObjects("후라이드치킨", 3)
                    syncPayload()
                    storage.heads.clear()
                    storage.lastModified.clear()

                    val empty = syncPayload()

                    empty.path("removalSkipped").asBoolean() shouldBe true
                    empty.path("removedCount").asInt() shouldBe 0
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%' AND status = 'ACTIVE'") shouldBe 3
                }
            }

            `when`("지워진 사진을 봇 리뷰가 참조하고 있으면") {
                then("그 리뷰의 image_refs 에서 떼고(detached), 사진이 0장이 되는 리뷰는 소프트 삭제한다(deleted) — 죽은 이미지가 남지 않는다") {
                    val bots = accountService.ensureBots(2).bots
                    val food = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 3)
                    syncPayload()
                    val p1 = poolPath("봇대상음식1", 1)
                    val p2 = poolPath("봇대상음식1", 2)
                    val review = reviewService.createBotReview(bots[0].id, food.id, 4, "The dish was warm and comforting, with a deep savory broth.", listOf(p1, p2), photoPool.ownerIds())
                    storage.delete(p1)

                    val afterFirst = syncPayload()

                    afterFirst.path("removedCount").asInt() shouldBe 1
                    afterFirst.path("detachedReviewCount").asInt() shouldBe 1
                    afterFirst.path("deletedReviewCount").asInt() shouldBe 0
                    imageRefsOf(review.reviewId) shouldBe listOf(p2)

                    storage.delete(p2)
                    val afterSecond = syncPayload()

                    afterSecond.path("removedCount").asInt() shouldBe 1
                    afterSecond.path("detachedReviewCount").asInt() shouldBe 0
                    afterSecond.path("deletedReviewCount").asInt() shouldBe 1
                    count("SELECT COUNT(*) FROM food_review WHERE id = ${review.reviewId} AND status = 'DELETED'") shouldBe 1
                    count("SELECT review_count FROM food WHERE id = ${food.id}") shouldBe 0
                }
            }

            `when`("같은 사진을 참조하는 봇 리뷰가 여럿(A p1 / B p1,p2 / C p1)일 때 p1 이 지워지면") {
                then("A·C 는 삭제되고 B 는 refs [p2] 가 DB 에 실제로 남으며 카운터가 맞는다 — 첫 삭제의 컨텍스트 clear 가 나머지 처리를 잃지 않는다") {
                    val bots = accountService.ensureBots(3).bots
                    val food = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 3)
                    syncPayload()
                    val p1 = poolPath("봇대상음식1", 1)
                    val p2 = poolPath("봇대상음식1", 2)
                    val owners = photoPool.ownerIds()
                    val a = reviewService.createBotReview(bots[0].id, food.id, 4, "The dish was warm and comforting, with a deep savory broth.", listOf(p1), owners)
                    val b = reviewService.createBotReview(bots[1].id, food.id, 4, "The dish was warm and comforting, with a deep savory broth.", listOf(p1, p2), owners)
                    val c = reviewService.createBotReview(bots[2].id, food.id, 4, "The dish was warm and comforting, with a deep savory broth.", listOf(p1), owners)
                    storage.delete(p1)

                    val result = syncPayload()

                    result.path("detachedReviewCount").asInt() shouldBe 1
                    result.path("deletedReviewCount").asInt() shouldBe 2
                    count("SELECT COUNT(*) FROM food_review WHERE id IN (${a.reviewId}, ${c.reviewId}) AND status = 'DELETED'") shouldBe 2
                    count("SELECT COUNT(*) FROM food_review WHERE id = ${b.reviewId} AND status = 'ACTIVE'") shouldBe 1
                    imageRefsOf(b.reviewId) shouldBe listOf(p2)
                    count("SELECT review_count FROM food WHERE id = ${food.id}") shouldBe 1
                    count("SELECT review_count FROM member WHERE id = ${bots[0].id}") shouldBe 0
                    count("SELECT review_count FROM member WHERE id = ${bots[1].id}") shouldBe 1
                    count("SELECT review_count FROM member WHERE id = ${bots[2].id}") shouldBe 0
                }
            }

            `when`("사라진 사진을 참조하는 리뷰 하나의 처리가 실패하면") {
                then("그 리뷰가 참조하던 사진 행은 ACTIVE 로 남아 다음 sync 가 다시 잡는다 — 리뷰 처리가 성공한 뒤에만 행을 지운다") {
                    val bots = accountService.ensureBots(2).bots
                    val food = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 3)
                    syncPayload()
                    val p1 = poolPath("봇대상음식1", 1)
                    val p2 = poolPath("봇대상음식1", 2)
                    val fine = reviewService.createBotReview(bots[0].id, food.id, 4, "The dish was warm and comforting, with a deep savory broth.", listOf(p2), photoPool.ownerIds())
                    val now = LocalDateTime.now()
                    val brokenId = dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO food_review (member_id, food_id, rating, content, image_refs, status, created_at, updated_at) VALUES (?, ?, 4, 'broken refs review text', ?, 'ACTIVE', ?, ?)",
                            java.sql.Statement.RETURN_GENERATED_KEYS,
                        ).use { ps ->
                            ps.setLong(1, bots[1].id); ps.setLong(2, food.id); ps.setString(3, """["$p1", {"not": "a path"}]"""); ps.setObject(4, now); ps.setObject(5, now)
                            ps.executeUpdate()
                            ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                        }
                    }
                    storage.delete(p1)
                    storage.delete(p2)

                    val first = syncPayload()

                    first.path("deletedReviewCount").asInt() shouldBe 1
                    count("SELECT COUNT(*) FROM food_review WHERE id = ${fine.reviewId} AND status = 'DELETED'") shouldBe 1
                    count("SELECT COUNT(*) FROM food_review WHERE id = $brokenId AND status = 'ACTIVE'") shouldBe 1
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path = '$p1' AND status = 'ACTIVE'") shouldBe 1
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path = '$p2' AND status = 'DELETED'") shouldBe 1
                    first.path("removedCount").asInt() shouldBe 1

                    dataSource.connection.use { c -> c.createStatement().use { it.execute("""UPDATE food_review SET image_refs = '["$p1"]' WHERE id = $brokenId""") } }
                    val second = syncPayload()

                    second.path("deletedReviewCount").asInt() shouldBe 1
                    second.path("removedCount").asInt() shouldBe 1
                    count("SELECT COUNT(*) FROM food_review WHERE id = $brokenId AND status = 'DELETED'") shouldBe 1
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path = '$p1' AND status = 'DELETED'") shouldBe 1
                }
            }

            `when`("봇의 review_count 가 이미 0 인 상태에서 그 봇의 리뷰가 사진 소실로 삭제되면") {
                then("sync 는 성공하고 리뷰는 삭제되며 카운터는 음수가 되지 않는다 — 한 리뷰의 실패가 전체를 되돌리지 않는다") {
                    val bots = accountService.ensureBots(2).bots
                    val food = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 2)
                    syncPayload()
                    val p1 = poolPath("봇대상음식1", 1)
                    val review = reviewService.createBotReview(bots[0].id, food.id, 4, "The dish was warm and comforting, with a deep savory broth.", listOf(p1), photoPool.ownerIds())
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET review_count = 0 WHERE id = ${bots[0].id}") } }
                    storage.delete(p1)

                    val result = syncPayload()

                    result.path("deletedReviewCount").asInt() shouldBe 1
                    count("SELECT COUNT(*) FROM food_review WHERE id = ${review.reviewId} AND status = 'DELETED'") shouldBe 1
                    count("SELECT review_count FROM member WHERE id = ${bots[0].id}") shouldBe 0
                    count("SELECT review_count FROM food WHERE id = ${food.id}") shouldBe 0
                }
            }

            `when`("삭제 대상이 기존 활성 풀 행의 절반을 넘으면") {
                then("잘린 목록으로 본다 — 삭제 단계를 건너뛰고(removalSkipped) 행은 그대로다") {
                    accountService.ensureBots(1)
                    stubObjects("후라이드치킨", 4)
                    syncPayload()
                    (1..3).forEach { storage.delete(poolPath("후라이드치킨", it)) }

                    val result = syncPayload()

                    result.path("removalSkipped").asBoolean() shouldBe true
                    result.path("removedCount").asInt() shouldBe 0
                    count("SELECT COUNT(*) FROM uploaded_image WHERE object_path LIKE '%images/review-bot/%' AND status = 'ACTIVE'") shouldBe 4
                }
            }

            `when`("페이지 크기 2로 오브젝트 5개를 훑으면") {
                then("여러 페이지를 끝까지 읽어 5장 등록하고, 1장을 지우면 삭제 반영도 전 페이지 기준으로 한다") {
                    accountService.ensureBots(1)
                    stubObjects("후라이드치킨", 5)
                    val paged = ReviewBotPhotoSyncService(storage, uploadedImageRepository, accountService, photoPool, reviewRepository, reviewService, transactionManager, pageSize = 2)

                    val first = paged.sync()
                    storage.delete(poolPath("후라이드치킨", 5))
                    val second = paged.sync()

                    first.registeredCount shouldBe 5
                    first.imagesPerKey[ReviewBotPhotoPool.keyOf("후라이드치킨")] shouldBe 5
                    second.registeredCount shouldBe 0
                    second.skippedCount shouldBe 4
                    second.removedCount shouldBe 1
                    second.removalSkipped shouldBe false
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

        given("풀 경로에 대한 일반 업로드 완료 신고 — POST /api/images/complete") {
            `when`("로그인 회원이 결정적 풀 경로로 완료 신고를 보내면") {
                then("400 으로 거절하고 스토리지 삭제도 행 생성도 없다 — 풀 사진을 지우거나 가로채지 못한다") {
                    val member = accountService.ensureBots(1).bots.single()
                    val path = poolPath("후라이드치킨", 1)
                    storage.stub(path, "image/webp", 100_001L)

                    val mismatch = mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(member.id, MemberRole.USER)}")
                        header("X-API-Version", "1.0")
                        contentType = org.springframework.http.MediaType.APPLICATION_JSON
                        content = """{"path":"$path","contentType":"image/webp","size":1}"""
                    }.andReturn().response
                    val exact = mockMvc.post("/api/images/complete") {
                        header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(member.id, MemberRole.USER)}")
                        header("X-API-Version", "1.0")
                        contentType = org.springframework.http.MediaType.APPLICATION_JSON
                        content = """{"path":"$path","contentType":"image/webp","size":100001}"""
                    }.andReturn().response

                    mismatch.status shouldBe 400
                    exact.status shouldBe 400
                    storage.deleted.size shouldBe 0
                    count("SELECT COUNT(*) FROM uploaded_image") shouldBe 0
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

            `when`("풀 없는 음식 5개가 후보 앞에 있고 풀 있는 음식 1개가 뒤에 있으면") {
                then("앞의 5개는 메모리에서 걸러 텍스트 생성 없이 지나가고, 뒤의 1개에 쓴다 — 생성 호출 1회, 요약에 풀 없음 5") {
                    accountService.ensureBots(2)
                    val withoutPool = (1..5).map { seedFood("봇대상음식$it") }
                    val withPool = seedFood("봇대상음식6")
                    stubObjects("봇대상음식6", 3)
                    syncPayload()

                    val summary = writer().writeDue(at(22))

                    botReviewFoodIds() shouldBe listOf(withPool.id)
                    fakeGenerator.requests.size shouldBe 1
                    summary.skippedNoPool shouldBe withoutPool.size
                    summary.written shouldBe 1
                }
            }

            `when`("목록 음식의 봇 리뷰 3건이 전부 삭제됐으면") {
                then("평생 상한은 활성 리뷰만 세므로 다시 우선순위 대상이다 — 사진 소실로 지워진 음식이 영영 빠지지 않는다(기존 순이면 id 가 앞선 일반 음식이 먼저다)") {
                    val bots = accountService.ensureBots(4).bots
                    seedFood("봇대상음식1")
                    val chicken = seedFood("후라이드치킨")
                    listOf("후라이드치킨", "봇대상음식1").forEach { stubObjects(it, 3) }
                    syncPayload()
                    val yesterday = LocalDateTime.now().minusDays(1)
                    bots.take(3).forEach { insertBotReview(it.id, chicken.id, yesterday) }
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food_review SET status = 'DELETED' WHERE food_id = ${chicken.id}") } }
                    val seeded = maxReviewId()

                    writer().writeDue(at(22))

                    botReviewFoodIds(seeded) shouldBe listOf(chicken.id)
                }
            }

            `when`("오늘 쓴 봇 리뷰가 모더레이션으로 삭제됐으면") {
                then("목록 음식이라도 같은 날 다시 쓰지 않는다 — 오늘 게이트는 상태 무관(후보 쿼리와 같은 기준)") {
                    val bots = accountService.ensureBots(3).bots
                    val chicken = seedFood("후라이드치킨")
                    stubObjects("후라이드치킨", 3)
                    syncPayload()
                    insertBotReview(bots[0].id, chicken.id, LocalDateTime.now().minusMinutes(5))
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food_review SET status = 'DELETED' WHERE food_id = ${chicken.id}") } }
                    val seeded = maxReviewId()

                    writer().writeDue(at(22))

                    botReviewFoodIds(seeded) shouldBe emptyList()
                }
            }

            `when`("목록 이름의 풀 없는 음식이 페이지 후보에도 나오면") {
                then("풀 없음은 1번만 센다 — 우선순위 단계에서 시도한 음식은 페이지에서 다시 세지 않는다") {
                    accountService.ensureBots(2)
                    seedFood("후라이드치킨")
                    val withPool = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 3)
                    syncPayload()

                    val summary = writer().writeDue(at(22))

                    summary.skippedNoPool shouldBe 1
                    botReviewFoodIds() shouldBe listOf(withPool.id)
                }
            }

            `when`("정지된 봇이 오늘 이미 목록 음식에 썼으면") {
                then("그 음식은 오늘 다시 쓰지 않는다 — 오늘 게이트도 봇 계정 전체 기준") {
                    val bots = accountService.ensureBots(3).bots
                    val chicken = seedFood("후라이드치킨")
                    stubObjects("후라이드치킨", 3)
                    syncPayload()
                    val suspended = bots.maxBy { it.id }
                    insertBotReview(suspended.id, chicken.id, LocalDateTime.now().minusMinutes(5))
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET member_status = 'SUSPENDED' WHERE id = ${suspended.id}") } }
                    val seeded = maxReviewId()

                    writer().writeDue(at(22))

                    botReviewFoodIds(seeded) shouldBe emptyList()
                }
            }

            `when`("풀 소유 봇이 정지·탈퇴된 뒤에도") {
                then("풀은 그대로 보이고 남은 봇이 사진을 붙여 쓴다 — 소유자 조건은 '봇 계정'이지 '활성 봇'이 아니다") {
                    val bots = accountService.ensureBots(3).bots
                    val food = seedFood("봇대상음식1")
                    stubObjects("봇대상음식1", 3)
                    syncPayload().path("registeredCount").asInt() shouldBe 3
                    val owner = bots.minBy { it.id }
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET member_status = 'SUSPENDED' WHERE id = ${owner.id}") } }

                    writer().writeDue(at(22))
                    val afterSuspended = maxReviewId()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET status = 'DELETED' WHERE id = ${owner.id}") } }
                    writer().writeDue(at(22, daysFromNow = 1))
                    val afterWithdrawn = maxReviewId()

                    afterSuspended shouldNotBe 0L
                    afterWithdrawn shouldNotBe afterSuspended
                    imageRefsOf(afterSuspended).size shouldBeInRange 1..3
                    imageRefsOf(afterWithdrawn).size shouldBeInRange 1..3
                    count("SELECT COUNT(*) FROM food_review WHERE food_id = ${food.id} AND member_id <> ${owner.id}") shouldBe 2
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
