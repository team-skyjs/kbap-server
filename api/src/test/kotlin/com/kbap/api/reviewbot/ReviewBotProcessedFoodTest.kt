package com.kbap.api.reviewbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.ImageUploadProperties
import com.kbap.api.review.ProcessedFoodReviewSwitch
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.review.ReviewJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.infra.llm.review.OpenAiReviewTextGenerator
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ProcessedFoodReviewDraft
import com.kbap.common.port.llm.ProcessedFoodReviewDraftRequest
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import javax.sql.DataSource

@IntegrationTest
class ReviewBotProcessedFoodTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var accountService: ReviewBotAccountService
    @Autowired private lateinit var fakeGenerator: FakeReviewTextGenerator
    @Autowired private lateinit var bot: ReviewBotProcessedFoodService
    @Autowired private lateinit var writeSwitch: ProcessedFoodReviewSwitch
    @Autowired private lateinit var lockProvider: LockProvider
    @Autowired private lateinit var reviewRepository: ReviewJpaRepository
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var uploadProperties: ImageUploadProperties
    @Autowired private lateinit var photoStorage: com.kbap.api.image.FakeStorageObjectStore
    @Autowired private lateinit var photoService: ReviewBotPhotoService
    @Autowired private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
    @Autowired private lateinit var processedFoodService: com.kbap.api.processedfood.ProcessedFoodService
    @Autowired private lateinit var candidateIndex: com.kbap.api.processedfood.ProcessedFoodCandidateIndex
    @Autowired private lateinit var cvsCatalog: com.kbap.api.processedfood.ProcessedFoodCvsCatalog
    @Autowired private lateinit var reviewService: com.kbap.api.review.ReviewService
    @Autowired private lateinit var photoPool: ReviewBotPhotoPool

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun rows(sql: String): List<List<Any?>> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) (1..rs.metaData.columnCount).map { rs.getObject(it) } else null }.toList() }
            }
        }
        fun seed(reportNo: String, name: String, maker: String, foodType: String, raw: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '${ProcessedFoodSnapshot.normalizeName(name)}', '$foodType', '$maker', '$raw', NULL, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return (rows("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").single().single() as Number).toLong()
        }
        fun botReviews(productId: Long): List<List<Any?>> =
            rows("SELECT r.member_id, r.rating, r.spiciness_rating, r.sweetness_rating, r.created_at, r.content FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.processed_food_id = $productId")

        var milk = 0L
        var kick = 0L

        beforeSpec { writeSwitch.writeEnabled = true }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            fakeGenerator.reset()
            photoStorage.heads.clear()
            accountService.ensureBots(15)
            milk = seed("1984044800212", "빙그레 바나나맛 우유", "빙그레", "가공유", "원유, 정제수, 설탕, 바나나농축과즙")
            kick = seed("199104611019", "바나나킥", "농심", "과자", "옥수수, 설탕, 바나나분말")
        }
        afterSpec {
            writeSwitch.writeEnabled = false
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("리뷰 봇 가공식품 프롬프트·가드(KB-951)") {
            `when`("가공식품 프롬프트를 만들면") {
                then("편의점에서 산 포장 상품 문맥이고, 식당·서빙 표현을 피하고, 맛 축을 JSON 으로 받는다") {
                    val system = OpenAiReviewTextGenerator.PROCESSED_SYSTEM_PROMPT
                    system.contains("convenience store") shouldBe true
                    system.contains("Avoid mentioning restaurants, servers, staff or table service") shouldBe true
                    system.contains("\"spiciness\"") shouldBe true
                    system.contains("\"sweetness\"") shouldBe true
                    val user = OpenAiReviewTextGenerator.processedUserPrompt(
                        ProcessedFoodReviewDraftRequest("Binggrae Banana Milk", "가공유", listOf("원유", "설탕"), "English", 5, 80, ReviewBotStyles.ALL[0], 1, null),
                    )
                    user.contains("Product type: 가공유") shouldBe true
                    user.contains("원유, 설탕") shouldBe true
                    user.contains(ReviewBotStyles.ALL[0]) shouldBe true
                    user.contains("exactly 1 sentence") shouldBe true
                }
            }
            `when`("LLM 응답 JSON 을 읽으면") {
                then("본문·맛 축을 꺼내고, 1~5 밖 정수는 끝값으로 맞추고(아주 매움이 순함으로 뒤집히지 않게), 코드 펜스를 벗기고, 본문이 없으면 실패한다") {
                    OpenAiReviewTextGenerator.parseProcessedDraft("""{"review":"Crunchy and sweet, nice snack","spiciness":null,"sweetness":4}""") shouldBe
                        ProcessedFoodReviewDraft("Crunchy and sweet, nice snack", null, 4)
                    OpenAiReviewTextGenerator.parseProcessedDraft("```json\n{\"review\":\"Spicy noodles, loved the kick\",\"spiciness\":9,\"sweetness\":1}\n```") shouldBe
                        ProcessedFoodReviewDraft("Spicy noodles, loved the kick", 5, 1)
                    OpenAiReviewTextGenerator.parseProcessedDraft("""{"review":"Barely sweet cracker, nice crunch","spiciness":"hot","sweetness":0}""") shouldBe
                        ProcessedFoodReviewDraft("Barely sweet cracker, nice crunch", null, 1)
                    shouldThrowAny { OpenAiReviewTextGenerator.parseProcessedDraft("""{"spiciness":2}""") }
                }
            }
            `when`("가공식품 문구를 가드로 보면") {
                then("기존 금지어에 더해 식당·직원·서빙 표현을 거절한다") {
                    ReviewBotContentGuard.isAcceptableForProcessedFood("Grabbed it at the convenience store, crunchy and sweet") shouldBe true
                    ReviewBotContentGuard.isAcceptableForProcessedFood("The staff were friendly and the chips were crunchy") shouldBe false
                    ReviewBotContentGuard.isAcceptableForProcessedFood("식당에서 먹었는데 바삭하고 달콤했어요 맛있어요") shouldBe false
                    ReviewBotContentGuard.isAcceptableForProcessedFood("Took a photo first, crunchy and sweet snack") shouldBe false
                }
            }
        }

        given("리뷰 봇 가공식품 일괄 작성(KB-951)") {
            `when`("dryRun 이면") {
                then("편의점 대표 라인 순서로 상품별 목표 수(상위 10 은 8~12, 나머지 3~6)만큼 미리보기를 주고 아무것도 쓰지 않는다") {
                    val result = bot.run("dev", dryRun = true, limit = 40, ids = null, topLines = 72, seed = 7)

                    val byProduct = result.items.groupBy { it.productId }
                    (byProduct.getValue(milk).size in 8..12) shouldBe true
                    (byProduct.getValue(kick).size in 3..6) shouldBe true
                    result.items.first().productId shouldBe milk
                    result.items.all { it.content.isNotBlank() && it.rating in 3..5 && it.productName.isNotBlank() } shouldBe true
                    result.items.map { it.botId to it.productId }.distinct().size shouldBe result.items.size
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "0"
                    fakeGenerator.processedRequests.first().foodType shouldBe "가공유"
                }
            }

            `when`("seed 로 실행하면") {
                then("같은 도메인 경로로 맛 축과 함께 저장하고, 작성일은 최근 4주 안에 흩어지며, 다시 불러도 목표 수를 넘지 않는다") {
                    fakeGenerator.processedReply = { ProcessedFoodReviewDraft("Sweet and smooth, so good lol", spiciness = null, sweetness = 4) }
                    val planned = bot.run("dev", dryRun = true, limit = 40, ids = listOf(kick), topLines = null, seed = 3).items.size

                    val first = bot.run("dev", dryRun = false, limit = 40, ids = listOf(kick), topLines = null, seed = 3)
                    val again = bot.run("dev", dryRun = false, limit = 40, ids = listOf(kick), topLines = null, seed = 3)

                    first.written shouldBe planned
                    again.written shouldBe 0
                    val saved = botReviews(kick)
                    saved.size shouldBe planned
                    saved.map { it[0] }.distinct().size shouldBe planned
                    saved.all { (it[3] as Number).toInt() == 4 && (it[2] as Number).toInt() == 1 } shouldBe true
                    val now = LocalDateTime.now()
                    saved.map { it[4] as LocalDateTime }.all { created -> created.isBefore(now.plusSeconds(1)) && created.isAfter(now.minusDays(29)) } shouldBe true
                }
            }

            `when`("LLM 이 맛 축을 비워(null) 주면") {
                then("맛 축은 항상 1~5 로 남긴다 — 해당 없는 축은 최저값 1(예진 11/2 \"선택 별점도 다 남기도록\")") {
                    fakeGenerator.processedReply = { ProcessedFoodReviewDraft("Smooth and mild, so good lol", spiciness = null, sweetness = null) }

                    val result = bot.run("dev", dryRun = false, limit = 2, ids = listOf(kick), topLines = null, seed = 3)

                    result.items.all { it.spiciness == 1 && it.sweetness == 1 } shouldBe true
                    botReviews(kick).all { (it[2] as Number).toInt() == 1 && (it[3] as Number).toInt() == 1 } shouldBe true
                }
            }

            `when`("이미 사람 리뷰가 있는 상품에 여러 건을 쓰면 — 공부 지적(#517)") {
                then("id 순서가 작성일 순서와 같고, 기존 리뷰보다 이른 날짜가 없다(목록은 id 내림차순이라 날짜가 섞여 보이지 않게)") {
                    com.kbap.api.food.FoodTestSeed.seedMemberAvoiding(dataSource, 95301L)
                    execute(
                        "INSERT INTO food_review (member_id, target_type, food_id, processed_food_id, rating, serving_speed_rating, staff_kindness_rating, content, status, created_at, updated_at, version) " +
                            "VALUES (95301, 'PROCESSED_FOOD', NULL, $kick, 5, 0, 0, '사람이 쓴 리뷰입니다 맛있어요', 'ACTIVE', NOW(6) - INTERVAL 1 DAY, NOW(6) - INTERVAL 1 DAY, 0)",
                    )
                    val human = rows("SELECT created_at FROM food_review WHERE member_id = 95301").single().single() as LocalDateTime

                    bot.run("dev", dryRun = false, limit = 6, ids = listOf(kick), topLines = null, seed = 3)

                    val dates = rows("SELECT r.created_at FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.processed_food_id = $kick ORDER BY r.id").map { it.single() as LocalDateTime }
                    (dates.size >= 3) shouldBe true
                    dates shouldBe dates.sorted()
                    dates.all { it.isAfter(human) } shouldBe true
                }
            }

            `when`("limit 이 작으면") {
                then("그만큼만 쓰고 다음 호출이 이어서 쓴다") {
                    bot.run("dev", dryRun = false, limit = 1, ids = listOf(kick), topLines = null, seed = 5).written shouldBe 1
                    bot.run("dev", dryRun = false, limit = 1, ids = listOf(kick), topLines = null, seed = 5).written shouldBe 1
                    botReviews(kick).size shouldBe 2
                }
            }

            `when`("생성물이 가공식품 가드에 끝내 걸리면") {
                then("그 리뷰는 쓰지 않고 실패로 센다") {
                    fakeGenerator.processedReply = { ProcessedFoodReviewDraft("The staff served it fast, very crunchy", null, 3) }
                    val result = bot.run("dev", dryRun = false, limit = 1, ids = listOf(kick), topLines = null, seed = 1)
                    result.written shouldBe 0
                    result.failedProductIds shouldBe listOf(kick)
                    botReviews(kick).size shouldBe 0
                }
            }
        }

        given("가공식품 리뷰 봇 동시성·사진·할당(Codex P2 #517)") {
            `when`("음식 봇 작성기와 같은 분산 락을 다른 쪽이 쥐고 있으면") {
                then("실행은 409 REVIEW-007 로 거절하고 아무것도 쓰지 않는다 — dryRun 은 그대로 돈다") {
                    val lock = lockProvider.lock(LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, Duration.ofMinutes(5), Duration.ZERO)).orElseThrow()
                    try {
                        val error = shouldThrow<BusinessException> { bot.run("dev", dryRun = false, limit = 40, ids = listOf(kick), topLines = null, seed = 3) }
                        error.errorCode shouldBe ErrorCode.REVIEW_BOT_BUSY
                        botReviews(kick).size shouldBe 0
                        (bot.run("dev", dryRun = true, limit = 40, ids = listOf(kick), topLines = null, seed = 3).items.isNotEmpty()) shouldBe true
                    } finally {
                        lock.unlock()
                    }
                }
            }
            `when`("업로드 기록은 있지만 저장소에 없는(쓸 수 없는) 풀 사진이 있으면") {
                then("사진 없이 텍스트만 쓴다 — 사진은 사진 서비스 재고·재확인을 거친다") {
                    val owner = (rows("SELECT MIN(id) FROM member WHERE is_bot = 1").single().single() as Number).toLong()
                    val path = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf("processed:199104611019") + "/1.webp"
                    execute(
                        "INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) " +
                            "VALUES ($owner, '$path', 'image/webp', 100000, 'ACTIVE', NOW(6), NOW(6))",
                    )

                    val result = bot.run("dev", dryRun = false, limit = 2, ids = listOf(kick), topLines = null, seed = 3)

                    result.written shouldBe 2
                    rows("SELECT image_refs FROM food_review WHERE processed_food_id = $kick").all { it.single() == null } shouldBe true
                }
            }
            `when`("오늘 봇이 가공식품 리뷰를 썼으면") {
                then("음식 봇 일일 할당(countReviewBotReviewsSince)에는 세지 않는다 — 음식 리뷰만 센다") {
                    bot.run("dev", dryRun = false, limit = 2, ids = listOf(kick), topLines = null, seed = 3)
                    val startOfDay = LocalDate.now().atStartOfDay().minusDays(30)
                    reviewRepository.countReviewBotReviewsSince(startOfDay) shouldBe 0

                    val food = foodRepository.save(Food(koreanName = "할당음식", description = "설명", contentStatus = FoodContentStatus.READY)).id
                    val owner = (rows("SELECT MIN(id) FROM member WHERE is_bot = 1").single().single() as Number).toLong()
                    execute(
                        "INSERT INTO food_review (member_id, target_type, food_id, rating, serving_speed_rating, staff_kindness_rating, content, status, created_at, updated_at, version) " +
                            "VALUES ($owner, 'FOOD', $food, 4, 0, 0, 'Warm and savory, so good', 'ACTIVE', NOW(6), NOW(6), 0)",
                    )
                    reviewRepository.countReviewBotReviewsSince(startOfDay) shouldBe 1
                }
            }
        }

        given("가공식품 봇 리뷰 사진 재활용 금지(S-935)") {
            fun stubPool(count: Int) {
                (1..count).forEach { n ->
                    photoStorage.stub(ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf("processed:199104611019") + "/$n.webp", "image/webp", 100_000L + n)
                }
                photoService.sync()
            }
            fun photoRefs(): List<String> =
                rows("SELECT CAST(image_refs AS CHAR) FROM food_review WHERE processed_food_id = $kick AND image_refs IS NOT NULL").map { it.single().toString() }

            `when`("상품 사진 풀이 1장이면") {
                then("그 사진은 리뷰 한 건에만 붙고 나머지는 텍스트만, 사진 붙은 리뷰는 약 30% 이하다") {
                    stubPool(1)
                    val result = bot.run("dev", dryRun = false, limit = 40, ids = listOf(kick), topLines = null, seed = 3)

                    (result.written >= 3) shouldBe true
                    photoRefs().size shouldBe 1
                    result.items.count { it.imageCount > 0 } shouldBe 1
                }
            }
            `when`("사진 풀이 여러 장이고 나눠 실행하면") {
                then("같은 경로가 두 리뷰에 붙지 않는다 — 기존 리뷰가 쓴 사진은 다음 실행에서 빠진다") {
                    stubPool(3)
                    bot.run("dev", dryRun = false, limit = 2, ids = listOf(kick), topLines = null, seed = 5)
                    bot.run("dev", dryRun = false, limit = 2, ids = listOf(kick), topLines = null, seed = 5)

                    val used = photoRefs().flatMap { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
                    used.size shouldBe used.distinct().size
                }
            }
            `when`("기존 봇 리뷰 여러 건이 같은 사진을 쓰고 있으면(dev 정리 API)") {
                then("dryRun 은 바꾸지 않고, 실행은 가장 이른 리뷰만 사진을 남기고 나머지는 사진만 뗀다(본문·updated_at 유지)") {
                    val path = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf("processed:199104611019") + "/1.webp"
                    val bots = rows("SELECT id FROM member WHERE is_bot = 1 ORDER BY id LIMIT 3").map { (it.single() as Number).toLong() }
                    bots.forEachIndexed { i, botId ->
                        execute(
                            "INSERT INTO food_review (member_id, target_type, processed_food_id, rating, serving_speed_rating, staff_kindness_rating, content, image_refs, status, created_at, updated_at, version) " +
                                "VALUES ($botId, 'PROCESSED_FOOD', $kick, 4, 0, 0, 'Crunchy and sweet snack $i', '[\"$path\"]', 'ACTIVE', '2026-10-0${i + 1} 12:00:00', '2026-10-0${i + 1} 12:00:00', 0)",
                        )
                    }

                    val dry = bot.dedupePhotos("dev", dryRun = true)
                    dry.reviewsChanged shouldBe 2
                    photoRefs().size shouldBe 3

                    bot.dedupePhotos("dev", dryRun = false).reviewsChanged shouldBe 2
                    val after = rows("SELECT content, CAST(image_refs AS CHAR), updated_at FROM food_review WHERE processed_food_id = $kick ORDER BY created_at")
                    after[0][1].toString().contains(path) shouldBe true
                    after.drop(1).all { it[1] == null } shouldBe true
                    after.map { it[0] } shouldBe listOf("Crunchy and sweet snack 0", "Crunchy and sweet snack 1", "Crunchy and sweet snack 2")
                    after.map { it[2].toString().startsWith("2026-10-0") } shouldBe listOf(true, true, true)
                    shouldThrow<BusinessException> { bot.dedupePhotos("prod", dryRun = true) }.errorCode shouldBe ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH
                }
            }
            `when`("운영(prod) 서버에서 헤더도 prod 로 정리 API 를 부르면 — 공부 지적(#520)") {
                then("헤더가 맞아도 거절한다 — dev 데이터 정리 전용(prod 는 별도 승인)") {
                    val prodBot = ReviewBotProcessedFoodService(
                        jdbcTemplate, processedFoodService, candidateIndex, cvsCatalog, accountService, reviewService, photoPool, photoService, lockProvider, fakeGenerator, "prod",
                    )
                    shouldThrow<BusinessException> { prodBot.dedupePhotos("prod", dryRun = false) }.errorCode shouldBe ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH
                    shouldThrow<BusinessException> { prodBot.dedupePhotos("prod", dryRun = true) }.errorCode shouldBe ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH
                }
            }
        }

        given("가공식품 봇 리뷰 작성일 분산(KB-966)") {
            val seoul = java.time.ZoneId.of("Asia/Seoul")
            fun datesOf(productId: Long): List<java.time.LocalDateTime> = botReviews(productId).map { (it[4] as java.time.LocalDateTime) }
            fun checkSpread(productIds: List<Long>, days: Long) {
                val now = java.time.LocalDateTime.now()
                val all = productIds.flatMap { id ->
                    val dates = datesOf(id)
                    dates.map { it.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(seoul).toLocalDate() }.distinct().size shouldBe dates.size
                    dates
                }
                all.forEach { at ->
                    (at.isAfter(now.minusDays(days)) && !at.isAfter(now)) shouldBe true
                    at.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(seoul).hour shouldBeInRange 8..23
                }
                all.map { it.toLocalDate() }.distinct().size shouldBeGreaterThan 5
            }
            `when`("spreadDays=30 으로 만들면") {
                then("작성일이 최근 30일 안(미래 없음)·KST 08~23시에 흩어지고, 한 상품의 리뷰는 같은 날에 몰리지 않는다") {
                    bot.run("dev", dryRun = false, limit = 40, ids = listOf(kick, milk), topLines = null, seed = 966, spreadDays = 30)
                    checkSpread(listOf(kick, milk), 30)
                    rows("SELECT COUNT(*) FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.updated_at <> r.created_at").single().single().toString() shouldBe "0"
                }
            }
            `when`("쓸 수 있는 날보다 리뷰가 많으면 — Codex P2(#527)") {
                then("생성은 그 상품을 날 수만큼만 쓰고 나머지를 skipped 로 알리며, 재분산은 400 과 필요한 최소 일수를 준다") {
                    val fresh = seed("CAP-1", "포카칩 오리지널", "오리온", "과자", "감자, 식물성유지, 정제소금")
                    val result = bot.run("dev", dryRun = false, limit = 40, ids = listOf(fresh), topLines = null, seed = 968, spreadDays = 1)
                    result.written shouldBe 1
                    result.skippedForDays shouldBeGreaterThan 0
                    datesOf(fresh).size shouldBe 1

                    val most = (rows("SELECT MAX(c) FROM (SELECT COUNT(*) c FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.status = 'ACTIVE' AND r.target_type = 'PROCESSED_FOOD' GROUP BY r.processed_food_id) t").single().single() as Number).toInt()
                    val error = shouldThrow<BusinessException> { bot.redistribute("dev", days = 1, dryRun = true, seed = 1) }
                    error.errorCode shouldBe ErrorCode.INVALID_REQUEST
                    (error.payload as Map<*, *>)["minDays"] shouldBe most
                }
            }
            `when`("기존 봇 리뷰를 재분산하면") {
                then("dryRun 은 아무것도 바꾸지 않고, 실행하면 같은 규칙으로 흩어지며 prod 는 거절한다") {
                    bot.run("dev", dryRun = false, limit = 40, ids = listOf(kick, milk), topLines = null, seed = 967)
                    execute("UPDATE food_review r JOIN member m ON m.id = r.member_id SET r.created_at = NOW(6), r.updated_at = NOW(6) WHERE m.is_bot = 1")
                    val before = rows("SELECT r.id, r.created_at FROM food_review r ORDER BY r.id")
                    val dry = bot.redistribute("dev", days = 30, dryRun = true, seed = 1)
                    dry.reviews shouldBe before.size
                    rows("SELECT r.id, r.created_at FROM food_review r ORDER BY r.id") shouldBe before

                    val applied = bot.redistribute("dev", days = 30, dryRun = false, seed = 1)
                    applied.reviews shouldBe before.size
                    checkSpread(listOf(kick, milk), 30)
                    rows("SELECT COUNT(*) FROM food_review WHERE updated_at <> created_at").single().single().toString() shouldBe "0"

                    shouldThrow<BusinessException> { bot.redistribute("prod", days = 30, dryRun = true, seed = 1) }.errorCode shouldBe ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH
                    shouldThrow<BusinessException> { bot.redistribute("dev", days = 0, dryRun = true, seed = 1) }.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        given("가공식품 리뷰 봇 어드민 API 가드") {
            fun call(target: String, body: Map<String, Any?>) = mockMvc.post("/api/admin/review-bots/processed-foods/reviews") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
                header("X-Target-Environment", target)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }
            `when`("요청 환경이 서버 환경과 다르면") {
                then("409 REVIEW-006 이고 생성하지 않는다") {
                    val body = call("prod", mapOf("ids" to listOf(kick))).andExpect { status { isConflict() } }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    mapper.readTree(body).path("code").asText() shouldBe "REVIEW-006"
                    fakeGenerator.processedRequests.size shouldBe 0
                }
            }
            `when`("seed 없이 실제 실행하거나 limit 이 범위 밖이면") {
                then("400 이다") {
                    call("dev", mapOf("ids" to listOf(kick), "dryRun" to false)).andExpect { status { isBadRequest() } }
                    call("dev", mapOf("ids" to listOf(kick), "limit" to 41)).andExpect { status { isBadRequest() } }
                }
            }
            `when`("기본값(dryRun=true)으로 부르면") {
                then("미리보기만 주고 저장하지 않는다") {
                    val body = call("dev", mapOf("ids" to listOf(kick))).andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    mapper.readTree(body).path("payload").path("items").size() shouldBe mapper.readTree(body).path("payload").path("planned").asInt()
                    botReviews(kick).size shouldBe 0
                }
            }
        }
    }
}
