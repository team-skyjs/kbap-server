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
import org.springframework.test.web.servlet.get
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
    @Autowired private lateinit var photos: ReviewBotPhotoService
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
        fun sync(force: Boolean = false) = mockMvc.post("/api/admin/review-bots/images/sync" + if (force) "?force=true" else "") {
            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
        }.andReturn().response
        fun syncPayload(force: Boolean = false) = mapper.readTree(sync(force).also { it.status shouldBe 200 }.contentAsString).path("payload")
        fun seedFood(koreanName: String): Food = foodRepository.save(Food(koreanName = koreanName, description = "설명", contentStatus = FoodContentStatus.READY))
        fun writer(min: Int = 1, max: Int = 1) = ReviewBotWriter(
            accountService, reviewRepository, foodRepository, ingredientRepository, reviewService, fakeGenerator, photos, priorityList, photoPool, min, max, Duration.ofMinutes(15),
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

        given("관리자 사진 재고 조회와 Swagger 계약") {
            `when`("관리자가 재고 GET을 호출하면") {
                then("공통 응답 봉투에 미등록 재고를 담고 등록행과 저장소는 변경하지 않는다") {
                    stubObjects("후라이드치킨", 1)
                    val response = mockMvc.get("/api/admin/review-bots/images") {
                        header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                        header("X-API-Version", "1.0")
                    }.andReturn().response
                    response.status shouldBe 200
                    val body = mapper.readTree(response.contentAsString)
                    body.path("success").asBoolean() shouldBe true
                    body.path("payload").path("unregistered").size() shouldBe 1
                    count("SELECT COUNT(*) FROM uploaded_image") shouldBe 0
                    storage.deleted.size shouldBe 0
                    mockMvc.get("/api/admin/review-bots/images").andReturn().response.status shouldBe 401
                }
            }
            `when`("버전별 Swagger 문서를 조회하면") {
                then("GET과 단일 sync POST 및 기존 응답 필드와 보류 목록을 명시한다") {
                    listOf("1.0", "1.1", "2.0").forEach { version ->
                        val response = mockMvc.get("/v3/api-docs/$version").andReturn().response
                        response.status shouldBe 200
                        val doc = mapper.readTree(response.contentAsString)
                        doc.path("paths").path("/api/admin/review-bots/images").has("get") shouldBe true
                        val sync = doc.path("paths").path("/api/admin/review-bots/images/sync").path("post")
                        sync.path("parameters").any { it.path("name").asText() == "force" } shouldBe true
                        val fields = doc.path("components").path("schemas").path("AdminReviewBotPhotoSyncResponse").path("properties")
                        listOf("registeredCount", "skippedCount", "restoredCount", "removedCount", "detachedReviewCount", "deletedReviewCount", "failedReviewCount", "unparseableReviewIds", "detectionFailed", "removalSkipped", "imagesPerKey", "held").forEach {
                            fields.has(it) shouldBe true
                        }
                    }
                }
            }
        }

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

            listOf(false, true).forEach { force ->
                `when`("사진이 사라지고 force=$force 로 동기화하면") {
                    then("등록행과 기존 리뷰를 유지하고 삭제·복구 카운터 0과 보류 사유를 반환한다") {
                        val bot = accountService.ensureBots(1).bots.single()
                        val food = seedFood("후라이드치킨")
                        stubObjects("후라이드치킨", 2)
                        syncPayload().path("registeredCount").asInt() shouldBe 2
                        val path = poolPath("후라이드치킨", 1)
                        val review = reviewService.createBotReview(bot.id, food.id, 4, "The chicken was crisp and juicy, a real treat.", listOf(path), setOf(bot.id))
                        storage.heads.clear()

                        val result = syncPayload(force)

                        listOf("removedCount", "restoredCount", "detachedReviewCount", "deletedReviewCount", "failedReviewCount").forEach {
                            result.path(it).asInt() shouldBe 0
                        }
                        result.path("removalSkipped").asBoolean() shouldBe true
                        result.path("held").map { it.path("reason").asText() } shouldBe listOf("MISSING_OBJECT", "MISSING_OBJECT")
                        count("SELECT COUNT(*) FROM uploaded_image WHERE status='ACTIVE'") shouldBe 2
                        imageRefsOf(review.reviewId) shouldBe listOf(path)
                        reviewRepository.findById(review.reviewId).isPresent shouldBe true
                        photos.getAvailablePaths(food.koreanName) shouldBe emptyList()
                        storage.deleted.size shouldBe 0
                    }
                }
            }

            `when`("DELETED 등록행의 사진이 다시 나타나면") {
                then("force로도 행을 복구하지 않고 소유·상태 충돌로 보류한다") {
                    accountService.ensureBots(1)
                    stubObjects("후라이드치킨", 1)
                    syncPayload()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE uploaded_image SET status='DELETED', updated_at=DATE_SUB(NOW(), INTERVAL 31 DAY)") } }

                    val result = syncPayload(true)

                    result.path("restoredCount").asInt() shouldBe 0
                    result.path("held").single().path("reason").asText() shouldBe "OWNER_OR_STATUS"
                    count("SELECT COUNT(*) FROM uploaded_image WHERE status='DELETED'") shouldBe 1
                    storage.deleted.size shouldBe 0
                }
            }

            `when`("500개를 넘는 사진 재고를 조회하면") {
                then("마지막 페이지까지 조회하고 저장소를 변경하지 않는다") {
                    stubObjects("후라이드치킨", 501)
                    val inventory = photos.getInventory()
                    inventory.unregistered.size shouldBe 501
                    inventory.held shouldBe emptyList()
                    storage.deleted.size shouldBe 0
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
                then("400 UPLOAD-004 로 거절하고 스토리지 삭제도 행 생성도 없다 — #395 의 발급 키 규칙이 풀 경로를 막는다") {
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
                    mapper.readTree(mismatch.contentAsString).path("code").asText() shouldBe "UPLOAD-004"
                    exact.status shouldBe 400
                    mapper.readTree(exact.contentAsString).path("code").asText() shouldBe "UPLOAD-004"
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
                then("평생 상한은 상태 무관이라 여전히 목록에서 제외되고, 그 봇들도 그 음식에 소진돼 일반 음식만 쓴다(#306 의미 유지 — 예진 결정 10/13)") {
                    val bots = accountService.ensureBots(3).bots
                    val plain = seedFood("봇대상음식1")
                    val chicken = seedFood("후라이드치킨")
                    listOf("후라이드치킨", "봇대상음식1").forEach { stubObjects(it, 3) }
                    syncPayload()
                    val yesterday = LocalDateTime.now().minusDays(1)
                    bots.take(3).forEach { insertBotReview(it.id, chicken.id, yesterday) }
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food_review SET status = 'DELETED' WHERE food_id = ${chicken.id}") } }
                    val seeded = maxReviewId()

                    writer(min = 2, max = 2).writeDue(at(22))

                    botReviewFoodIds(seeded) shouldBe listOf(plain.id)
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

            `when`("풀 소유 봇이 정지·탈퇴되면") {
                then("소유 변경 없이 보류하고 새 리뷰 작성에서 제외한다") {
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

                    afterSuspended shouldBe 0L
                    afterWithdrawn shouldBe 0L
                    photos.getInventory().held.map { it.reason }.toSet() shouldBe setOf("OWNER_OR_STATUS")
                    count("SELECT COUNT(*) FROM uploaded_image WHERE member_id = ${owner.id} AND status='ACTIVE'") shouldBe 3
                    count("SELECT COUNT(*) FROM food_review WHERE food_id = ${food.id}") shouldBe 0
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
