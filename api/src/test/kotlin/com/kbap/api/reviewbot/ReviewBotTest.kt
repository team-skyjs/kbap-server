package com.kbap.api.reviewbot

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.ReviewBotForbiddenCategory
import com.kbap.common.infra.llm.review.OpenAiReviewTextGenerator
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlin.random.Random

@IntegrationTest
class ReviewBotTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var accountService: ReviewBotAccountService
    @Autowired private lateinit var reviewRepository: ReviewJpaRepository
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var ingredientRepository: IngredientJpaRepository
    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var fakeGenerator: FakeReviewTextGenerator
    @Autowired private lateinit var writerBean: ReviewBotWriter
    @Autowired private lateinit var lockProvider: LockProvider

    private val seoul = ZoneId.of("Asia/Seoul")

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

        fun ensure(count: Int) = mockMvc.post("/api/admin/review-bots") {
            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"count":$count}"""
        }

        fun seedFoods(n: Int): List<Food> = (1..n).map {
            foodRepository.save(Food(koreanName = "봇대상음식$it", description = "설명 $it", contentStatus = FoodContentStatus.READY))
        }

        fun writer(min: Int = 8, max: Int = 12, budget: Duration = Duration.ofMinutes(15)) = ReviewBotWriter(
            accountService, reviewRepository, foodRepository, ingredientRepository, reviewService, fakeGenerator, min, max, budget,
        )

        fun todayAt(hour: Int): ZonedDateTime = ZonedDateTime.now(seoul).withHour(hour).withMinute(0).withSecond(0).withNano(0)

        fun botReviewCount(): Long = count("SELECT COUNT(*) FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1")

        beforeEach {
            TestTables.clearAll(dataSource)
            fakeGenerator.reset()
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("리뷰 봇 계정 — POST /api/admin/review-bots") {
            `when`("count 를 여러 번 보내면") {
                then("모자란 수만 만든다(멱등) — 로그인 불가 합성 uid·온보딩 완료·기기 없음") {
                    ensure(12).andExpect { status { isOk() }; jsonPath("$.payload.createdCount") { value(12) } }
                    ensure(12).andExpect { jsonPath("$.payload.createdCount") { value(0) } }
                    ensure(15).andExpect {
                        jsonPath("$.payload.createdCount") { value(3) }
                        jsonPath("$.payload.bots.length()") { value(15) }
                    }

                    count("SELECT COUNT(*) FROM member WHERE is_bot = 1") shouldBe 15
                    count("SELECT COUNT(*) FROM member WHERE is_bot = 1 AND provider_uid NOT LIKE 'review-bot:%' ESCAPE '\\\\'") shouldBe 0
                    count("SELECT COUNT(*) FROM member WHERE is_bot = 1 AND onboarding_completed = 0") shouldBe 0
                    count("SELECT COUNT(*) FROM notification_device d JOIN member m ON m.id = d.member_id WHERE m.is_bot = 1") shouldBe 0
                }
            }

            `when`("두 요청이 동시에 오면") {
                then("이름 잠금으로 직렬화돼 최종 개수가 count 와 같다(2배가 되지 않는다)") {
                    val executor = Executors.newFixedThreadPool(2)
                    val gate = CountDownLatch(1)
                    val results = (1..2).map { executor.submit<Int> { gate.await(); ensure(10).andReturn().response.status } }
                    gate.countDown()
                    results.map { it.get() } shouldBe listOf(200, 200)
                    executor.shutdown()

                    count("SELECT COUNT(*) FROM member WHERE is_bot = 1") shouldBe 10
                }
            }

            `when`("count 가 범위 밖이면") {
                then("400 이다") {
                    ensure(0).andExpect { status { isBadRequest() } }
                    ensure(51).andExpect { status { isBadRequest() } }
                }
            }
        }

        given("리뷰 봇 작성") {
            `when`("하루 끝(22시)까지 돌면") {
                then("그날 목표만큼 쓰고, 한 음식엔 하루 1개·같은 봇·같은 음식 중복 0, 기존 ReviewService 경로라 봇의 review_count 가 오른다") {
                    accountService.ensureBots(12)
                    seedFoods(20)
                    val now = todayAt(22)
                    val plan = ReviewBotDailyPlan.of(now.toLocalDate(), 8, 12, 9, 22)

                    writer().writeDue(now)

                    botReviewCount() shouldBe plan.target.toLong()
                    count("SELECT COUNT(*) FROM (SELECT food_id FROM food_review GROUP BY food_id HAVING COUNT(*) > 1) t") shouldBe 0
                    count("SELECT COUNT(*) FROM (SELECT member_id, food_id FROM food_review GROUP BY member_id, food_id HAVING COUNT(*) > 1) t") shouldBe 0
                    count("SELECT COALESCE(SUM(review_count), 0) FROM member WHERE is_bot = 1") shouldBe plan.target.toLong()
                    count("SELECT COUNT(*) FROM food_review WHERE rating NOT BETWEEN 3 AND 5 OR serving_speed_rating <> 0 OR staff_kindness_rating <> 0 OR image_refs IS NOT NULL OR place_name IS NOT NULL") shouldBe 0
                }
            }

            `when`("같은 시각에 다시 돌면") {
                then("더 쓰지 않는다 — 누적 목표에서 오늘 쓴 수를 뺀 만큼만 쓴다") {
                    accountService.ensureBots(12)
                    seedFoods(20)
                    val now = todayAt(22)
                    writer().writeDue(now)
                    val first = botReviewCount()

                    writer().writeDue(now)

                    botReviewCount() shouldBe first
                }
            }

            `when`("음식이 하루 목표보다 적어 틱이 여러 번 돌면") {
                then("한 음식엔 하루 봇 리뷰 1개뿐이라 음식 수에서 멈춘다 — 다른 봇이라도 같은 날 같은 음식에 또 쓰지 않는다") {
                    accountService.ensureBots(12)
                    seedFoods(3)
                    val now = todayAt(22)

                    writer(min = 12, max = 12).writeDue(now)
                    writer(min = 12, max = 12).writeDue(now)

                    botReviewCount() shouldBe 3
                    count("SELECT COUNT(*) FROM (SELECT food_id FROM food_review GROUP BY food_id HAVING COUNT(*) > 1) t") shouldBe 0
                }
            }

            `when`("어제 그 음식에 리뷰를 쓴 봇만 있으면") {
                then("오늘 그 봇이 같은 음식에 또 쓰지 않는다 — 같은 봇·같은 음식 중복 0") {
                    val bot = accountService.ensureBots(1).bots.single()
                    val food = seedFoods(1).single()
                    val yesterday = java.time.LocalDateTime.now().minusDays(1)
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO food_review (member_id, food_id, rating, content, status, created_at, updated_at) VALUES (?, ?, 4, 'yesterday review text', 'ACTIVE', ?, ?)",
                        ).use { ps ->
                            ps.setLong(1, bot.id); ps.setLong(2, food.id); ps.setObject(3, yesterday); ps.setObject(4, yesterday)
                            ps.executeUpdate()
                        }
                    }

                    writer(min = 1, max = 1).writeDue(todayAt(22))

                    botReviewCount() shouldBe 1
                }
            }

            `when`("09시 틱이면") {
                then("그 시각까지 배정된 수만 쓴다") {
                    accountService.ensureBots(12)
                    seedFoods(20)
                    val now = todayAt(9)
                    val plan = ReviewBotDailyPlan.of(now.toLocalDate(), 8, 12, 9, 22)

                    writer().writeDue(now)

                    botReviewCount() shouldBe plan.dueUntil(9).toLong()
                }
            }

            `when`("리뷰 없는 음식과 리뷰 많은 음식이 있으면") {
                then("리뷰 없는 음식부터 채운다") {
                    accountService.ensureBots(3)
                    val (empty, crowded) = seedFoods(2)
                    val human = accountService.ensureBots(0).bots.first()
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE member SET is_bot = 0 WHERE id = ${human.id}") }
                    }
                    reviewService.createReview(human.id, crowded.id, 5, null, null, "사람이 쓴 리뷰입니다 아주 맛있어요", null, null)

                    writer(min = 1, max = 1).writeDue(todayAt(22))

                    query("SELECT r.food_id FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1")
                        .map { (it.single() as Number).toLong() } shouldBe listOf(empty.id)
                }
            }

            `when`("생성물이 금지어(가게·사진·알레르기 등)에 걸리면") {
                then("재생성까지 걸리면 저장하지 않는다") {
                    accountService.ensureBots(5)
                    seedFoods(5)
                    fakeGenerator.reply = { "Loved this place, the restaurant staff were great and the photo does not do it justice." }

                    writer().writeDue(todayAt(22))

                    botReviewCount() shouldBe 0
                    (fakeGenerator.requests.size > 0).shouldBeTrue()
                }
            }

            `when`("시간 예산이 다 됐으면") {
                then("남은 후보를 버리고 멈춘다 — 락이 풀린 뒤 실행이 이어지지 않게") {
                    accountService.ensureBots(5)
                    seedFoods(5)

                    writer(budget = Duration.ZERO).writeDue(todayAt(22))

                    botReviewCount() shouldBe 0
                }
            }

            `when`("다른 인스턴스가 잠금을 쥐고 있으면") {
                then("조용히 넘어가 아무것도 쓰지 않는다 — 풀리면 쓴다") {
                    accountService.ensureBots(5)
                    seedFoods(5)
                    val lock = lockProvider.lock(
                        LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, Duration.ofMinutes(5), Duration.ZERO),
                    ).orElseThrow()

                    writerBean.writeDue(todayAt(22))
                    botReviewCount() shouldBe 0

                    lock.unlock()
                    writerBean.writeDue(todayAt(22))
                    (botReviewCount() > 0).shouldBeTrue()
                }
            }
        }

        given("리뷰 봇 언어·별점·금지어") {
            `when`("일본 봇의 언어를 많이 뽑으면") {
                then("영어가 약 70%, 나머지는 국적 언어다") {
                    val random = Random(42)
                    val draws = List(10_000) { ReviewBotCountries.languageFor("JP", random) }
                    (draws.count { it == ReviewBotCountries.ENGLISH } / 10_000.0).shouldBeBetween(0.68, 0.72, 0.0)
                    draws.filterNot { it == ReviewBotCountries.ENGLISH }.toSet() shouldBe setOf("Japanese")
                }
            }

            `when`("별점을 많이 뽑으면") {
                then("3~5 이고 4 가 가장 많다") {
                    val random = Random(7)
                    val ratings = List(10_000) { ReviewBotCountries.rating(random) }
                    ratings.toSet().forEach { it shouldBeIn listOf(3, 4, 5) }
                    ratings.groupingBy { it }.eachCount().maxBy { it.value }.key shouldBe 4
                }
            }

            `when`("지원 언어 6개마다 알레르기/안전·가게·사진 금지 문장을 검사하면") {
                then("언어별로 세 종류 모두 거절하고, 각 언어에 세 종류 키워드가 다 있다") {
                    val sentences = mapOf(
                        "en" to listOf("Safe to eat even with a nut allergy, the broth was rich.", "The restaurant near the station served it hot.", "I took a photo before eating, it looked amazing."),
                        "ko" to listOf("알레르기 걱정 없이 먹었어요 국물이 진해요.", "역 근처 식당에서 먹었는데 따뜻했어요.", "먹기 전에 사진부터 찍었어요 정말 예뻐요."),
                        "ja" to listOf("アレルギーがあっても食べても安全でした、スープが濃厚。", "駅前のお店で食べました、とても温かかった。", "食べる前に写真を撮りました、とても綺麗。"),
                        "zh" to listOf("对海鲜过敏的人也可以安全食用，汤很浓郁。", "车站旁边的餐厅做得很地道，热乎乎的。", "吃之前先拍了照片，看起来很诱人。"),
                        "th" to listOf("คนแพ้ถั่วก็กินได้ปลอดภัย น้ำซุปเข้มข้นมาก", "ร้านใกล้สถานีทำได้อร่อยมาก ร้อนกำลังดี", "ถ่ายรูปก่อนกิน สวยมากเลย"),
                        "vi" to listOf("An toàn cho người bị dị ứng hải sản, nước dùng rất đậm đà.", "Quán gần ga làm món này rất ngon và nóng.", "Tôi chụp ảnh trước khi ăn, trông rất hấp dẫn."),
                    )
                    sentences.keys shouldBe ReviewBotContentGuard.LANGUAGES
                    sentences.forEach { (_, list) -> list.forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false } }
                    ReviewBotContentGuard.LANGUAGES.forEach { lang ->
                        ReviewBotContentGuard.coverage(lang).forEach { (category, terms) -> (terms.isNotEmpty()).shouldBeTrue() }
                    }
                }
            }

            `when`("영어 안전·알레르기 활용형이 들어간 문장을 검사하면") {
                then("구절이 아니라 어간으로 잡아 전부 거절한다") {
                    listOf(
                        "Children can safely eat this dish, the broth is mild and comforting.",
                        "This is the safest choice on the table for picky eaters, very tasty.",
                        "An allergy-friendly bowl of noodles that still tastes rich and warm.",
                        "Allergen free and delicious, the rice cakes were perfectly chewy.",
                        "Hypoallergenic ingredients only, and the flavor was still deep and savory.",
                    ).forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false }
                }
            }

            `when`("안전이 맛과 무관한 뜻으로 쓰인 문장을 검사하면") {
                then("오탐이지만 거절한다 — 리뷰가 안전을 언급하는 것 자체를 막는 결정이다") {
                    ReviewBotContentGuard.isAcceptable("The atmosphere felt safe and cozy while the stew kept bubbling away.") shouldBe false
                }
            }

            `when`("가드의 금지 범주와 프롬프트의 금지 문장을 대조하면") {
                then("가드 범주 = 프롬프트 금지 범주 5종 1:1 — 프롬프트는 강제 경계가 아니므로 가드가 같은 목록에서 나와야 한다") {
                    ReviewBotContentGuard.CATEGORIES shouldBe ReviewBotForbiddenCategory.entries.toSet()
                    ReviewBotContentGuard.CATEGORIES.size shouldBe 5
                    ReviewBotForbiddenCategory.entries.forEach { category ->
                        OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains(category.promptRule).shouldBeTrue()
                    }
                }
            }

            `when`("식이 적합성·건강 효능 문장을 언어별로 검사하면") {
                then("6개 언어 3문장씩 전부 거절한다") {
                    mapOf(
                        "en" to listOf("A vegan-friendly bowl that still tastes deeply savory.", "This is halal and gluten-free, and the broth is wonderful.", "It felt really good for your digestion and heart, and so tasty."),
                        "ko" to listOf("비건도 먹을 수 있는 메뉴인데 국물이 진해요.", "할랄 인증이라 안심이고 맛도 좋아요.", "몸에 좋은 재료라 건강해지는 느낌, 맛도 훌륭해요."),
                        "ja" to listOf("ビーガンでも食べられる、スープが濃厚です。", "ハラル対応でグルテンも入っていない、美味しい。", "体にいい食材で健康的、味も最高です。"),
                        "zh" to listOf("纯素也能吃，汤底很浓郁。", "清真认证，无麸质，味道很好。", "很养生也很健康，味道也很棒。"),
                        "th" to listOf("วีแกนก็กินได้ น้ำซุปเข้มข้นมาก", "ฮาลาลและปราศจากกลูเตน อร่อยมาก", "ดีต่อสุขภาพและช่วยลดน้ำหนัก อร่อยด้วย"),
                        "vi" to listOf("Món thuần chay mà vẫn rất đậm đà.", "Halal và không gluten, nước dùng tuyệt vời.", "Rất tốt cho sức khỏe và giúp giảm cân, lại ngon."),
                    ).forEach { (_, list) -> list.forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false } }
                }
            }

            `when`("건강이 맛 표현으로 쓰인 문장을 검사하면") {
                then("오탐이지만 거절한다 — 리뷰가 건강·식이를 언급하는 것 자체를 막는 결정이다") {
                    ReviewBotContentGuard.isAcceptable("A healthy portion of noodles with a bright, tangy broth.") shouldBe false
                }
            }

            `when`("금지어가 들어간 문장을 검사하면") {
                then("가게·사진·알레르기·안전 판단·URL 은 거절하고 맛 이야기는 통과한다") {
                    listOf(
                        "The restaurant was lovely and the soup was hot.",
                        "Great photo spot, and the noodles were chewy.",
                        "Safe to eat for people with a nut allergy, very tasty.",
                        "알레르기 걱정 없이 먹었어요 국물이 진해요.",
                        "このお店のスープが美味しかったです、また来たい。",
                        "Check www.example.com for the recipe, loved the broth.",
                    ).forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false }
                    ReviewBotContentGuard.isAcceptable("The broth was rich and spicy, perfect with a bowl of rice.") shouldBe true
                }
            }
        }
    }
}
