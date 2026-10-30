package com.kbap.api.reviewbot

import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.api.image.FakeStorageObjectStore
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
import org.springframework.test.web.servlet.get
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
    @Autowired private lateinit var photos: ReviewBotPhotoService
    @Autowired private lateinit var photoCatalog: ReviewBotPhotoCatalog
    @Autowired private lateinit var botPhotoRepository: com.kbap.common.domain.image.BotPhotoJpaRepository
    @Autowired private lateinit var photoStorage: FakeStorageObjectStore
    @Autowired private lateinit var writerBean: ReviewBotWriter
    @Autowired private lateinit var lockProvider: LockProvider
    @Autowired private lateinit var priorityList: ReviewBotPriorityList
    @Autowired private lateinit var photoPool: ReviewBotPhotoPool
    @Autowired private lateinit var uploadProperties: com.kbap.api.image.ImageUploadProperties

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
            accountService, reviewRepository, foodRepository, ingredientRepository, reviewService, fakeGenerator, photos, priorityList, photoPool, min, max, budget,
        )

        fun seedPoolsForAllFoods() {
            val owner = query("SELECT MIN(id) FROM member WHERE is_bot = 1").single().single() as? Number ?: return
            val prefix = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) " +
                        "SELECT ?, ?, 'image/webp', 100000, 'ACTIVE', NOW(6), NOW(6) FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM uploaded_image u WHERE u.object_path = ?)",
                ).use { ps ->
                    query("SELECT korean_name FROM food").map { it.single() as String }.forEach { name ->
                        (1..3).forEach { n ->
                            val path = prefix + ReviewBotPhotoPool.keyOf(name) + "/$n.webp"
                            photoStorage.stub(path, "image/webp", 100000)
                            ps.setLong(1, owner.toLong()); ps.setString(2, path); ps.setString(3, path); ps.addBatch()
                        }
                    }
                    ps.executeBatch()
                }
            }
        }

        fun run(w: ReviewBotWriter, now: ZonedDateTime) {
            seedPoolsForAllFoods()
            w.writeDue(now)
        }

        fun todayAt(hour: Int): ZonedDateTime = ZonedDateTime.now(seoul).withHour(hour).withMinute(0).withSecond(0).withNano(0)

        fun botReviewCount(): Long = count("SELECT COUNT(*) FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1")

        beforeEach {
            TestTables.clearAll(dataSource)
            fakeGenerator.reset()
            photoStorage.heads.clear()
            photoStorage.lastModified.clear()
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

            `when`("최대 수(50)까지 만들면") {
                then("닉네임이 전부 봇 규칙(소문자 사람 이름 + 네 번째 글자 겹침)이고 서로 겹치지 않는다") {
                    ensure(50).andExpect { status { isOk() } }

                    val nicknames = query("SELECT nickname FROM member WHERE is_bot = 1").map { it.single() as String }

                    nicknames.size shouldBe 50
                    nicknames.toSet().size shouldBe 50
                    nicknames.filterNot { Regex("^[a-z]{5,14}$").matches(it) && it[3] == it[4] && it[2] != it[3] } shouldBe emptyList()
                }
            }

            `when`("이미 봇이 있는 상태에서 더 만들면") {
                then("있던 봇의 닉네임은 건너뛴다") {
                    accountService.ensureBots(30)

                    accountService.ensureBots(50)

                    count("SELECT COUNT(DISTINCT nickname) FROM member WHERE is_bot = 1") shouldBe 50
                }
            }

            `when`("api-docs 의 설명을 보면") {
                then("닉네임을 옛 형식(음식_숫자)이라 적지 않고, 서버가 정하는 사람 이름형 소문자라고만 적는다") {
                    val description = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                        .readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8))
                        .path("paths").path("/api/admin/review-bots").path("post").path("description").asText()

                    description.contains("음식_숫자") shouldBe false
                    description.contains("사람 이름형 소문자") shouldBe true
                    description.contains("겹") shouldBe false
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

        given("등록된 사진의 메타데이터 재검증 — Codex P2") {
            `when`("등록된 사진이 비-webp 나 0바이트로 덮어써지면") {
                then("재고에서 available 이 아니라 held(MISSING_OR_INVALID_METADATA) 로 분류되고 새 리뷰에 쓰이지 않는다") {
                    accountService.ensureBots(1)
                    val food = seedFoods(1).single()
                    seedPoolsForAllFoods()
                    val prefix = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf(food.koreanName)
                    photoStorage.stub("$prefix/2.webp", "image/png", 100000)
                    photoStorage.stub("$prefix/3.webp", "image/webp", 0)

                    val inventory = photos.getInventory()

                    inventory.available shouldBe listOf("$prefix/1.webp")
                    inventory.held.filter { it.reason == "MISSING_OR_INVALID_METADATA" }.map { it.path }.sorted() shouldBe listOf("$prefix/2.webp", "$prefix/3.webp")
                    writer(min = 1, max = 1).writeDue(todayAt(22))
                    query("SELECT image_refs FROM food_review").map { it.single().toString() } shouldBe listOf("[\"$prefix/1.webp\"]")
                }
            }
        }

        given("사진 sync 의 키별 개수·일반 참조 판정 — Codex P2 2차") {
            fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
            fun humanMember(uid: String): Long {
                execute("INSERT INTO member (provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) VALUES ('GOOGLE', '$uid', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))")
                return count("SELECT id FROM member WHERE provider_uid = '$uid'")
            }

            `when`("저장소에 있는 사진 하나가 일반 리뷰 참조로 보류되면") {
                then("imagesPerKey 는 그 키의 저장소 객체 3개를 모두 센다 — 보류된 객체도 빠지지 않는다") {
                    accountService.ensureBots(1)
                    val food = seedFoods(1).single()
                    val key = ReviewBotPhotoPool.keyOf(food.koreanName)
                    val prefix = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + key
                    (1..3).forEach { photoStorage.stub("$prefix/$it.webp", "image/webp", 100000) }
                    val human = humanMember("human-ref")
                    execute("INSERT INTO food_review (member_id, food_id, rating, image_refs, status) VALUES ($human, ${food.id}, 5, JSON_ARRAY('$prefix/3.webp'), 'ACTIVE')")

                    val result = photos.sync()

                    result.registeredCount shouldBe 2
                    result.held.map { it.reason } shouldBe listOf("ORDINARY_REFERENCE")
                    result.imagesPerKey shouldBe mapOf(key to 3)
                }
            }

            `when`("일반 리뷰가 후보 경로의 '_' 자리에 다른 글자를 가진 경로를 참조하면") {
                then("와일드카드로 오인하지 않는다 — 후보는 일반 참조가 아니다") {
                    accountService.ensureBots(1)
                    val food = seedFoods(1).single()
                    val candidate = "local/images/review-bot/aa_bbbbbbbbb/1.webp"
                    val human = humanMember("human-wild")
                    execute("INSERT INTO food_review (member_id, food_id, rating, image_refs, status) VALUES ($human, ${food.id}, 5, JSON_ARRAY('local/images/review-bot/aaXbbbbbbbbb/1.webp'), 'ACTIVE')")

                    photoCatalog.getOrdinaryReferencedPaths(listOf(candidate)) shouldBe emptySet()
                    botPhotoRepository.hasOrdinaryReference(candidate) shouldBe 0L
                    photoCatalog.getOrdinaryReferencedPaths(listOf("local/images/review-bot/aaXbbbbbbbbb/1.webp")) shouldBe setOf("local/images/review-bot/aaXbbbbbbbbb/1.webp")
                }
            }
        }

        given("틱당 사진 재고 1회 — Codex P2") {
            `when`("한 틱에 여러 음식을 쓰면") {
                then("봇 사진 접두 전체 목록은 틱마다 한 번만 훑는다(후보마다 다시 훑지 않는다)") {
                    accountService.ensureBots(12)
                    seedFoods(20)
                    seedPoolsForAllFoods()
                    photoStorage.fullListingStarts.clear()

                    writer().writeDue(todayAt(22))

                    (botReviewCount() > 1) shouldBe true
                    photoStorage.fullListingStarts.size shouldBe 1
                }
            }
        }

        given("사진이 없는 새 리뷰 작성 방지") {
            `when`("사진이 없거나 생성 도중 소실되면") {
                then("LLM 호출 전 또는 저장 전에 제외한다") {
                    accountService.ensureBots(1)
                    seedFoods(1)
                    writer(min = 1, max = 1).writeDue(todayAt(22))
                    fakeGenerator.requests.size shouldBe 0
                    botReviewCount() shouldBe 0
                    fakeGenerator.reply = {
                        photoStorage.heads.clear()
                        FakeReviewTextGenerator.DEFAULT_REPLY
                    }
                    run(writer(min = 1, max = 1), todayAt(22))
                    fakeGenerator.requests.size shouldBe 1
                    botReviewCount() shouldBe 0
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

                    run(writer(), now)

                    botReviewCount() shouldBe plan.target.toLong()
                    count("SELECT COUNT(*) FROM (SELECT food_id FROM food_review GROUP BY food_id HAVING COUNT(*) > 1) t") shouldBe 0
                    count("SELECT COUNT(*) FROM (SELECT member_id, food_id FROM food_review GROUP BY member_id, food_id HAVING COUNT(*) > 1) t") shouldBe 0
                    count("SELECT COALESCE(SUM(review_count), 0) FROM member WHERE is_bot = 1") shouldBe plan.target.toLong()
                    count("SELECT COUNT(*) FROM food_review WHERE rating NOT BETWEEN 3 AND 5 OR serving_speed_rating <> 0 OR staff_kindness_rating <> 0 OR image_refs IS NULL OR JSON_LENGTH(image_refs) NOT BETWEEN 1 AND 3 OR place_name IS NOT NULL") shouldBe 0
                }
            }

            `when`("같은 시각에 다시 돌면") {
                then("더 쓰지 않는다 — 누적 목표에서 오늘 쓴 수를 뺀 만큼만 쓴다") {
                    accountService.ensureBots(12)
                    seedFoods(20)
                    val now = todayAt(22)
                    run(writer(), now)
                    val first = botReviewCount()

                    run(writer(), now)

                    botReviewCount() shouldBe first
                }
            }

            `when`("음식이 하루 목표보다 적어 틱이 여러 번 돌면") {
                then("한 음식엔 하루 봇 리뷰 1개뿐이라 음식 수에서 멈춘다 — 다른 봇이라도 같은 날 같은 음식에 또 쓰지 않는다") {
                    accountService.ensureBots(12)
                    seedFoods(3)
                    val now = todayAt(22)

                    run(writer(min = 12, max = 12), now)
                    run(writer(min = 12, max = 12), now)

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

                    run(writer(min = 1, max = 1), todayAt(22))

                    botReviewCount() shouldBe 1
                }
            }

            `when`("오늘 쓴 봇 리뷰가 모더레이션으로 삭제됐으면") {
                then("그 음식엔 다시 쓰지 않고(삭제를 봇이 되돌리지 않는다), 하루 목표는 다른 음식에 1건 대체한다") {
                    val bots = accountService.ensureBots(2).bots
                    val foods = seedFoods(2)
                    val moderated = foods.minBy { it.id }
                    val earlier = todayAt(10).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO food_review (member_id, food_id, rating, content, status, created_at, updated_at) VALUES (?, ?, 4, 'deleted bot review', 'DELETED', ?, ?)",
                        ).use { ps -> ps.setLong(1, bots[0].id); ps.setLong(2, moderated.id); ps.setObject(3, earlier); ps.setObject(4, earlier); ps.executeUpdate() }
                    }

                    run(writer(min = 1, max = 1), todayAt(22))

                    count("SELECT COUNT(*) FROM food_review WHERE food_id = ${moderated.id} AND status = 'ACTIVE'") shouldBe 0
                    count("SELECT COUNT(*) FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.status = 'ACTIVE'") shouldBe 1
                }
            }

            `when`("어제 그 음식에 쓴 유일한 봇의 리뷰가 모더레이션으로 삭제됐으면") {
                then("그 봇은 그 음식을 소진한 것으로 남는다 — 같은 봇이 같은 음식에 다시 쓰지 않는다") {
                    val bot = accountService.ensureBots(1).bots.single()
                    val food = seedFoods(1).single()
                    val yesterday = java.time.LocalDateTime.now().minusDays(1)
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO food_review (member_id, food_id, rating, content, status, created_at, updated_at) VALUES (?, ?, 4, 'deleted old review', 'DELETED', ?, ?)",
                        ).use { ps -> ps.setLong(1, bot.id); ps.setLong(2, food.id); ps.setObject(3, yesterday); ps.setObject(4, yesterday); ps.executeUpdate() }
                    }

                    run(writer(min = 1, max = 1), todayAt(22))

                    count("SELECT COUNT(*) FROM food_review WHERE member_id = ${bot.id} AND status = 'ACTIVE'") shouldBe 0
                }
            }

            `when`("09시 틱이면") {
                then("그 시각까지 배정된 수만 쓴다") {
                    accountService.ensureBots(12)
                    seedFoods(20)
                    val now = todayAt(9)
                    val plan = ReviewBotDailyPlan.of(now.toLocalDate(), 8, 12, 9, 22)

                    run(writer(), now)

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

                    run(writer(min = 1, max = 1), todayAt(22))

                    query("SELECT r.food_id FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1")
                        .map { (it.single() as Number).toLong() } shouldBe listOf(empty.id)
                }
            }

            `when`("활성 봇 전부가 이미 리뷰한 저리뷰 음식이 후보를 채우면") {
                then("그 음식은 후보에서 빠지고, 봇이 아직 안 쓴 음식이 리뷰 수가 더 많아도 선택된다 — 기아 없음") {
                    val bot = accountService.ensureBots(1).bots.single()
                    val human = accountService.ensureBots(2).bots.last()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET is_bot = 0 WHERE id = ${human.id}") } }
                    val exhausted = seedFoods(3)
                    val untouched = foodRepository.save(Food(koreanName = "미소진음식", description = "설명", contentStatus = FoodContentStatus.READY))
                    val yesterday = java.time.LocalDateTime.now().minusDays(1)
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO food_review (member_id, food_id, rating, content, status, created_at, updated_at) VALUES (?, ?, 4, 'old review text here', 'ACTIVE', ?, ?)",
                        ).use { ps ->
                            exhausted.forEach { f -> ps.setLong(1, bot.id); ps.setLong(2, f.id); ps.setObject(3, yesterday); ps.setObject(4, yesterday); ps.addBatch() }
                            repeat(2) { ps.setLong(1, human.id); ps.setLong(2, untouched.id); ps.setObject(3, yesterday); ps.setObject(4, yesterday); ps.addBatch() }
                            ps.executeBatch()
                        }
                    }

                    val seededUpTo = count("SELECT COALESCE(MAX(id), 0) FROM food_review")

                    run(writer(min = 1, max = 1), todayAt(22))

                    query("SELECT r.food_id FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.id > $seededUpTo")
                        .map { (it.single() as Number).toLong() } shouldBe listOf(untouched.id)
                }
            }

            `when`("봇 2명 중 1명만 어떤 음식을 소진했으면") {
                then("그 음식은 여전히 후보이고 나머지 봇이 쓴다") {
                    val bots = accountService.ensureBots(2).bots
                    val food = seedFoods(1).single()
                    val yesterday = java.time.LocalDateTime.now().minusDays(1)
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO food_review (member_id, food_id, rating, content, status, created_at, updated_at) VALUES (?, ?, 4, 'old review text here', 'ACTIVE', ?, ?)",
                        ).use { ps -> ps.setLong(1, bots[0].id); ps.setLong(2, food.id); ps.setObject(3, yesterday); ps.setObject(4, yesterday); ps.executeUpdate() }
                    }

                    val seededUpTo = count("SELECT COALESCE(MAX(id), 0) FROM food_review")

                    run(writer(min = 1, max = 1), todayAt(22))

                    query("SELECT r.member_id FROM food_review r WHERE r.food_id = ${food.id} AND r.id > $seededUpTo")
                        .map { (it.single() as Number).toLong() } shouldBe listOf(bots[1].id)
                }
            }

            `when`("생성물이 금지어(가게·사진·알레르기 등)에 걸리면") {
                then("재생성까지 걸리면 저장하지 않는다") {
                    accountService.ensureBots(5)
                    seedFoods(5)
                    fakeGenerator.reply = { "Loved it, the restaurant staff were great." }

                    run(writer(), todayAt(22))

                    botReviewCount() shouldBe 0
                    (fakeGenerator.requests.size > 0).shouldBeTrue()
                }
            }

            `when`("생성물이 20자는 넘지만 앞뒤 공백을 빼면 10자에 못 미치면") {
                then("재생성까지 못 미치면 저장하지 않고 건너뛴다 — 봇 리뷰 수·카운터 불변") {
                    accountService.ensureBots(5)
                    seedFoods(5)
                    fakeGenerator.reply = { "      Tasty!!       " }

                    run(writer(), todayAt(22))

                    botReviewCount() shouldBe 0
                    count("SELECT COALESCE(SUM(review_count), 0) FROM member WHERE is_bot = 1") shouldBe 0
                    (fakeGenerator.requests.size > 0).shouldBeTrue()
                }
            }

            `when`("첫 생성물만 앞뒤 공백을 빼면 10자에 못 미치면") {
                then("필터가 버리고 재생성한 본문으로 저장한다") {
                    accountService.ensureBots(1)
                    seedFoods(1)
                    val good = "Deep savory broth, would order again."
                    fakeGenerator.reply = { if (fakeGenerator.requests.size == 1) "      Tasty!!       " else good }

                    run(writer(), todayAt(22))

                    fakeGenerator.requests.size shouldBe 2
                    query("SELECT content FROM food_review").single().single() shouldBe good
                }
            }

            `when`("생성물이 언어별 앱 카드 상한을 넘으면 — KB-943") {
                then("재생성까지 넘으면 저장하지 않고 건너뛴다(자르지 않는다)") {
                    accountService.ensureBots(5)
                    seedFoods(5)
                    fakeGenerator.reply = { "So good. ".repeat(10).trim() }

                    run(writer(), todayAt(22))

                    botReviewCount() shouldBe 0
                    (fakeGenerator.requests.size > 0).shouldBeTrue()
                }
                then("첫 초안만 넘으면 재생성한 짧은 본문으로 저장하고, 요청에 언어별 상한을 싣는다") {
                    accountService.ensureBots(1)
                    seedFoods(1)
                    val short = "Crispy edges, chewy inside lol"
                    fakeGenerator.reply = { if (fakeGenerator.requests.size == 1) "So good. ".repeat(10).trim() else short }

                    run(writer(), todayAt(22))

                    fakeGenerator.requests.size shouldBe 2
                    query("SELECT content FROM food_review").single().single() shouldBe short
                    fakeGenerator.requests.forEach { it.maxCharacters shouldBe ReviewBotContentGuard.maxLengthFor(it.language) }
                }
            }

            `when`("시간 예산이 다 됐으면") {
                then("남은 후보를 버리고 멈춘다 — 다음 페이지도 열지 않는다(락이 풀린 뒤 실행이 이어지지 않게)") {
                    accountService.ensureBots(5)
                    seedFoods(5)

                    run(writer(budget = Duration.ZERO), todayAt(22))

                    botReviewCount() shouldBe 0
                    fakeGenerator.requests.size shouldBe 0
                }
            }

            `when`("첫 페이지(due×3)의 음식이 전부 가드에 걸리면") {
                then("다음 키셋 페이지(리뷰 수·id 커서)로 넘어가 뒤쪽 음식으로 due 를 채운다 — 같은 틱에서 같은 음식은 다시 시도하지 않는다") {
                    accountService.ensureBots(3)
                    val human = accountService.ensureBots(4).bots.last()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET is_bot = 0 WHERE id = ${human.id}") } }
                    val blocked = (1..3).map { foodRepository.save(Food(koreanName = "가드실패음식$it", description = "설명", contentStatus = FoodContentStatus.READY)) }
                    val behind = foodRepository.save(Food(koreanName = "뒷페이지음식", description = "설명", contentStatus = FoodContentStatus.READY))
                    reviewService.createReview(human.id, behind.id, 5, null, null, "사람이 쓴 리뷰입니다 아주 맛있어요", null, null)
                    fakeGenerator.reply = {
                        if (it.foodName.contains("가드실패")) "Loved it, the restaurant staff were great."
                        else FakeReviewTextGenerator.DEFAULT_REPLY
                    }

                    run(writer(min = 1, max = 1), todayAt(22))

                    query("SELECT r.food_id FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1")
                        .map { (it.single() as Number).toLong() } shouldBe listOf(behind.id)
                    fakeGenerator.requests.count { it.foodName.contains("가드실패") } shouldBe blocked.size * 2
                }
            }

            `when`("가드에 걸리는 음식이 페이지 상한(5페이지)을 넘게 있으면") {
                then("상한에서 멈춘다 — 그 뒤 음식은 이번 틱에 닿지 않고 LLM 호출도 상한만큼만") {
                    accountService.ensureBots(3)
                    val human = accountService.ensureBots(4).bots.last()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET is_bot = 0 WHERE id = ${human.id}") } }
                    val blocked = (1..15).map { foodRepository.save(Food(koreanName = "가드실패음식$it", description = "설명", contentStatus = FoodContentStatus.READY)) }
                    val behind = foodRepository.save(Food(koreanName = "뒷페이지음식", description = "설명", contentStatus = FoodContentStatus.READY))
                    reviewService.createReview(human.id, behind.id, 5, null, null, "사람이 쓴 리뷰입니다 아주 맛있어요", null, null)
                    fakeGenerator.reply = {
                        if (it.foodName.contains("가드실패")) "Loved it, the restaurant staff were great."
                        else FakeReviewTextGenerator.DEFAULT_REPLY
                    }

                    run(writer(min = 1, max = 1), todayAt(22))

                    botReviewCount() shouldBe 0
                    fakeGenerator.requests.size shouldBe blocked.size * 2
                }
            }

            `when`("다른 인스턴스가 잠금을 쥐고 있으면") {
                then("조용히 넘어가 아무것도 쓰지 않는다 — 풀리면 쓴다") {
                    accountService.ensureBots(5)
                    seedFoods(5)
                    val lock = lockProvider.lock(
                        LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, Duration.ofMinutes(5), Duration.ZERO),
                    ).orElseThrow()

                    run(writerBean, todayAt(22))
                    botReviewCount() shouldBe 0

                    lock.unlock()
                    run(writerBean, todayAt(22))
                    (botReviewCount() > 0).shouldBeTrue()
                }
            }
        }

        given("리뷰 봇 문구 다양화(KB-943 — 형식이 너무 똑같음)") {
            `when`("같은 난수로 요청을 만들면") {
                then("스타일·문장 수가 같고, 스타일은 목록 안·문장 수는 1 또는 2 다") {
                    val food = seedFoods(1).single()
                    val a = writerBean.draftRequestFor(food, "English", 5, Random(42), null)
                    val b = writerBean.draftRequestFor(food, "English", 5, Random(42), null)
                    a.style shouldBe b.style
                    a.sentences shouldBe b.sentences
                    (a.style in ReviewBotStyles.ALL).shouldBeTrue()
                    (a.sentences in 1..2).shouldBeTrue()
                    (1L..200L).map { writerBean.draftRequestFor(food, "English", 5, Random(it), null).style }.toSet() shouldBe ReviewBotStyles.ALL.toSet()
                }
            }

            `when`("사용자 프롬프트를 만들면") {
                then("고른 스타일·정확한 문장 수·직전 봇 리뷰(있을 때만)를 싣는다") {
                    val request = ReviewDraftRequest("Bibimbap", "rice bowl", listOf("rice"), "English", 4, 80, ReviewBotStyles.ALL[3], 1, "Earlier bot text, fine")
                    val prompt = OpenAiReviewTextGenerator.userPrompt(request)
                    prompt.contains(ReviewBotStyles.ALL[3]) shouldBe true
                    prompt.contains("exactly 1 sentence") shouldBe true
                    prompt.contains("Earlier bot text, fine") shouldBe true
                    OpenAiReviewTextGenerator.userPrompt(request.copy(sentences = 2, avoidSimilarTo = null)).let {
                        it.contains("exactly 2 sentences") shouldBe true
                        it.contains("Another traveler") shouldBe false
                    }
                }
                then("시스템 프롬프트는 상투구와 형용사 셋 나열 시작을 피하라고 한다(금지 줄 형태는 쓰지 않는다)") {
                    listOf("Loved it", "seriously", "great with rice", "perfect", "three adjectives").forEach {
                        OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains(it) shouldBe true
                    }
                }
            }

            `when`("같은 음식에 이미 봇 리뷰가 있으면") {
                then("가장 최근 봇 리뷰 본문을 '이것과 다르게' 참고로 요청에 싣는다") {
                    accountService.ensureBots(3)
                    val food = seedFoods(1).single()
                    val earlier = (query("SELECT MIN(id) FROM member WHERE is_bot = 1").single().single() as Number).toLong()
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "INSERT INTO food_review (member_id, target_type, food_id, rating, serving_speed_rating, staff_kindness_rating, content, language, status, created_at, updated_at, version) " +
                                    "VALUES ($earlier, 'FOOD', ${food.id}, 4, 0, 0, 'Earlier bot text, fine', 'en', 'ACTIVE', NOW(6), NOW(6), 0)",
                            )
                        }
                    }

                    run(writer(min = 1, max = 1), todayAt(22))

                    fakeGenerator.requests.first().avoidSimilarTo shouldBe "Earlier bot text, fine"
                }
            }
        }

        given("리뷰 봇 앱 카드 길이 상한(KB-943)") {
            `when`("언어별 상한을 보면") {
                then("라틴·키릴 80, 한중일 40, 태국어 60 이고 모르는 언어는 80 이다") {
                    mapOf(
                        "English" to 80, "Vietnamese" to 80, "Spanish" to 80, "Indonesian" to 80, "Russian" to 80,
                        "Korean" to 40, "Japanese" to 40, "Simplified Chinese" to 40, "Traditional Chinese" to 40,
                        "Thai" to 60, "Klingon" to 80,
                    ).forEach { (language, limit) -> ReviewBotContentGuard.maxLengthFor(language) shouldBe limit }
                }
            }
            `when`("상한 경계를 재면") {
                then("상한 글자 수까지는 통과, 한 글자 넘으면 거절 — 코드 포인트로 센다") {
                    ReviewBotContentGuard.fitsCard("a".repeat(80), "English") shouldBe true
                    ReviewBotContentGuard.fitsCard("a".repeat(81), "English") shouldBe false
                    ReviewBotContentGuard.fitsCard("美".repeat(40), "Japanese") shouldBe true
                    ReviewBotContentGuard.fitsCard("美".repeat(41), "Japanese") shouldBe false
                    ReviewBotContentGuard.fitsCard("𠀀".repeat(40), "Traditional Chinese") shouldBe true
                    ReviewBotContentGuard.fitsCard("  ${"a".repeat(80)}  ", "English") shouldBe true
                }
            }
            `when`("시스템 프롬프트를 보면") {
                then("한두 문장의 가벼운 인터넷 말투를 요구하고 기존 금지 규칙은 그대로다") {
                    OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains("1 or 2 short sentences") shouldBe true
                    OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains("without mixing in expressions from other languages") shouldBe true
                    OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains("ㅋㅋ") shouldBe false
                    OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains("no emoji, no hashtags") shouldBe true
                    OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains("2 to 4 sentences") shouldBe false
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

            `when`("어떤 알레르겐이든 X-free 로 쓴 문장을 검사하면") {
                then("알레르겐을 열거하지 않고 \"\\w+[- ]?free\" 하나로 전부 거절한다") {
                    listOf(
                        "Peanut-free and still packed with nutty flavor, the sauce was lovely.",
                        "Egg free noodles that had a wonderful chew and a spicy kick.",
                        "A shellfish-free broth that still tasted deeply of the sea.",
                        "Soy-free version was just as savory, with a great smoky finish.",
                        "Không chứa đậu phộng mà vẫn đậm đà, nước sốt rất ngon.",
                    ).forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false }
                }
            }

            `when`("X-free 가 알레르겐과 무관하게 쓰인 문장을 검사하면") {
                then("오탐이지만 거절한다 — 리뷰 텍스트에 그 표현 자체가 필요 없다는 결정이다") {
                    ReviewBotContentGuard.isAcceptable("A hassle-free lunch, the bibimbap came out hot and well mixed.") shouldBe false
                }
            }

            `when`("안전이 맛과 무관한 뜻으로 쓰인 문장을 검사하면") {
                then("오탐이지만 거절한다 — 리뷰가 안전을 언급하는 것 자체를 막는 결정이다") {
                    ReviewBotContentGuard.isAcceptable("The atmosphere felt safe and cozy while the stew kept bubbling away.") shouldBe false
                }
            }

            `when`("가드의 금지 범주와 프롬프트의 금지 문장을 대조하면") {
                then("가드 범주 = 프롬프트 금지 범주 6종 1:1 — 프롬프트는 강제 경계가 아니므로 가드가 같은 목록에서 나와야 한다") {
                    ReviewBotContentGuard.CATEGORIES shouldBe ReviewBotForbiddenCategory.entries.toSet()
                    ReviewBotContentGuard.CATEGORIES.size shouldBe 6
                    ReviewBotForbiddenCategory.entries.forEach { category ->
                        OpenAiReviewTextGenerator.SYSTEM_PROMPT.contains(category.promptRule).shouldBeTrue()
                    }
                }
            }

            `when`("시스템 프롬프트의 금지 문장(never·do not·don't·must not)을 줄 단위로 훑으면") {
                then("전부 enum 의 promptRule 에서 나온 줄이다 — 프롬프트엔 있는데 가드에 없는 범주는 구조적으로 불가능하다") {
                    val prohibition = Regex("(?i)\\b(never|do not|don't|must not)\\b")
                    val prohibitionLines = OpenAiReviewTextGenerator.SYSTEM_PROMPT.lines().filter { prohibition.containsMatchIn(it) }.toSet()
                    prohibitionLines shouldBe ReviewBotForbiddenCategory.entries.map { "- ${it.promptRule}" }.toSet()
                }
            }

            `when`("각 범주의 promptRule 에 쓰인 명사를 그 범주 패턴에 대조하면") {
                then("프롬프트가 금지한다고 말한 단어는 가드도 전부 안다 — 6범주 모두") {
                    ReviewBotForbiddenCategory.entries.forEach { category ->
                        category.promptNouns.isNotEmpty().shouldBeTrue()
                        category.promptNouns.forEach { noun ->
                            category.promptRule.lowercase().contains(noun.lowercase()).shouldBeTrue()
                            category.patterns().any { it.containsMatchIn(noun) }.shouldBeTrue()
                        }
                    }
                }
            }

            `when`("장소·주소·위치 표현이 든 문장을 언어별로 검사하면") {
                then("전부 거절한다") {
                    listOf(
                        "The location near the station made it easy to find, and the soup was hot.",
                        "Ask for the address if you want it, but the noodles were the real deal.",
                        "역 근처 위치라 찾기 쉬웠고 국물이 진했어요.",
                        "駅の近くの場所で、スープが濃厚でした。",
                        "车站附近的位置，汤很浓郁。",
                        "ที่อยู่ใกล้สถานี น้ำซุปเข้มข้นมาก",
                        "Địa chỉ gần ga, nước dùng rất đậm đà.",
                    ).forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false }
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

            `when`("가격·비용·싸다·비싸다 문장을 언어별로 검사하면") {
                then("6개 언어 3문장씩 전부 거절한다 — 통화 기호·단위 + 숫자, 어간, 언어별 대응어") {
                    val sentences = mapOf(
                        "en" to listOf("It was only 8,000 won and the broth was rich.", "Cheap but tasty, the noodles had a great chew.", "A bit expensive for what you get, though the sauce was lovely."),
                        "ko" to listOf("8000원인데 국물이 진해요.", "가격 대비 양이 많고 맛도 좋아요.", "조금 비싸지만 소스가 훌륭해요."),
                        "ja" to listOf("値段のわりにスープが濃厚でした。", "安いのに美味しい、麺のコシが最高。", "少し高いけどソースが絶品。"),
                        "zh" to listOf("价格不贵，汤底很浓郁。", "很便宜又好吃，面条很有嚼劲。", "有点贵，但酱汁很棒。"),
                        "th" to listOf("ราคาไม่แพง น้ำซุปเข้มข้นมาก", "ถูกและอร่อย เส้นเหนียวนุ่ม", "แพงไปหน่อย แต่ซอสอร่อยมาก"),
                        "vi" to listOf("Giá chỉ 50.000 đ mà nước dùng rất đậm đà.", "Rẻ mà ngon, sợi mì dai vừa phải.", "Hơi đắt nhưng nước sốt rất tuyệt."),
                    )
                    sentences.keys shouldBe ReviewBotContentGuard.LANGUAGES
                    sentences.forEach { (_, list) -> list.forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false } }
                    listOf("\$12 for this bowl, and the kimchi was crisp.", "Paid ₩9,000 and the portion was generous.", "I paid for a second bowl because the broth was that good.")
                        .forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false }
                    listOf(
                        "Only 12€ and the broth was rich and spicy.",
                        "12000₩ for a bowl this big, the noodles were chewy.",
                        "\$12 well spent, the kimchi was crisp and tangy.",
                        "฿120 and the portion was generous, sauce was lovely.",
                        "12,000₫ for the soup, and it was deeply savory.",
                        "8000円で、スープが濃厚でした。",
                    ).forEach { ReviewBotContentGuard.isAcceptable(it) shouldBe false }
                }
            }

            `when`("가치 표현이 값과 무관하게 쓰인 문장을 검사하면") {
                then("\"worth every bite\" 는 통과하고, 값싼 뜻이 아닌 \"cheap\" 은 오탐이지만 거절한다") {
                    ReviewBotContentGuard.isAcceptable("Worth every bite, the broth was rich and the rice cakes chewy.") shouldBe true
                    ReviewBotContentGuard.isAcceptable("Giá đỗ was crunchy and the broth deeply savory, a lovely bowl.") shouldBe true
                    ReviewBotContentGuard.isAcceptable("The sauce tasted a little cheap, but the noodles were chewy.") shouldBe false
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
