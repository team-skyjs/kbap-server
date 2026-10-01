package com.kbap.api.translation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class TranslationControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var translator: FakeTextTranslator
    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var translationRepository: ContentTranslationJpaRepository
    @Autowired private lateinit var translationService: TranslationService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val mapper = jacksonObjectMapper()

    init {
        val viewer = 6781L
        val author = 6782L
        val blockedAuthor = 6783L
        val food = 6780L
        val deletedFood = 6789L
        val visible = 67801L
        val deletedReview = 67802L
        val ofDeletedFood = 67803L
        val byBlockedAuthor = 67804L
        val ownOfDeletedFood = 67805L
        val emptyBody = 67806L
        val othersReviews = setOf(visible, deletedReview, ofDeletedFood, byBlockedAuthor, emptyBody)

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun scalar(sql: String): String? = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }

        fun review(id: Long, memberId: Long, foodId: Long, content: String?, status: String = "ACTIVE") = exec(
            "INSERT INTO food_review (id, member_id, food_id, rating, content, status) " +
                "VALUES ($id, $memberId, $foodId, 5, ${content?.let { "'$it'" } ?: "NULL"}, '$status')",
        )

        fun seed() {
            TestTables.clearAll(dataSource)
            translator.reset()
            listOf(viewer, author, blockedAuthor).forEach { id ->
                exec(
                    "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, review_count, " +
                        "unique_reviewed_food_count, created_at, updated_at) VALUES ($id, 'GOOGLE', 'translation-$id', 'ACTIVE', 1, 'ACTIVE', 0, 0, NOW(6), NOW(6))",
                )
            }
            listOf(food to "ACTIVE", deletedFood to "DELETED").forEach { (id, status) ->
                exec(
                    "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, ingredients, " +
                        "content_status, status, created_at, updated_at) VALUES ($id, '번역음식$id', '설명', 0, '{}', '{}', '[]', 'READY', '$status', NOW(6), NOW(6))",
                )
            }
            exec("INSERT INTO member_block (blocker_member_id, blocked_member_id) VALUES ($viewer, $blockedAuthor)")
            review(visible, author, food, "The broth was rich and so tasty")
            review(deletedReview, author, food, "deleted review body", status = "DELETED")
            review(ofDeletedFood, author, deletedFood, "review of a deleted food")
            review(byBlockedAuthor, blockedAuthor, food, "review by a blocked member")
            review(ownOfDeletedFood, viewer, deletedFood, "my review of a deleted food")
            review(emptyBody, author, food, null)
        }

        fun viewerToken() = tokenIssuer.issueAccessToken(viewer, MemberRole.USER)

        fun translate(reviewId: Long, lang: String? = "ko", token: String? = null, targetType: String = "REVIEW"): MockHttpServletResponse =
            mockMvc.post("/api/translations${lang?.let { "?lang=$it" }.orEmpty()}") {
                header("X-API-Version", "1.0")
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("targetType" to targetType, "targetId" to reviewId))
            }.andReturn().response

        fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

        fun rows(): Long = scalar("SELECT COUNT(*) FROM content_translation")!!.toLong()

        fun providerOf(translator: com.kbap.common.port.llm.TextTranslator?): org.springframework.beans.factory.ObjectProvider<com.kbap.common.port.llm.TextTranslator> =
            org.springframework.beans.factory.support.DefaultListableBeanFactory().apply {
                translator?.let { registerSingleton("textTranslator", it) }
            }.getBeanProvider(com.kbap.common.port.llm.TextTranslator::class.java)

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        given("리뷰 번역 — POST /api/translations") {
            `when`("게스트가 보이는 리뷰를 번역하면") {
                then("200 으로 대상·언어·번역문을 주고 캐시 행이 하나 생긴다") {
                    seed()

                    val response = translate(visible)

                    response.status shouldBe 200
                    val payload = body(response).path("payload")
                    payload.path("targetType").asText() shouldBe "REVIEW"
                    payload.path("targetId").asLong() shouldBe visible
                    payload.path("language").asText() shouldBe "ko"
                    payload.path("text").asText() shouldBe "[ko] The broth was rich and so tasty"
                    rows() shouldBe 1L
                    translator.calls.single() shouldBe ("The broth was rich and so tasty" to LanguageCode.KO)
                }
            }

            `when`("같은 리뷰·언어를 다시 요청하면") {
                then("저장본을 돌려주고 번역 엔진을 다시 부르지 않는다") {
                    seed()
                    translate(visible)

                    val again = translate(visible)

                    again.status shouldBe 200
                    body(again).path("payload").path("text").asText() shouldBe "[ko] The broth was rich and so tasty"
                    translator.calls.size shouldBe 1
                    rows() shouldBe 1L
                }
            }

            `when`("다른 언어로 요청하면") {
                then("언어마다 따로 번역해 따로 저장한다") {
                    seed()
                    translate(visible, lang = "ko")

                    body(translate(visible, lang = "ja")).path("payload").path("text").asText() shouldBe "[ja] The broth was rich and so tasty"

                    translator.calls.size shouldBe 2
                    rows() shouldBe 2L
                }
            }

            `when`("리뷰 본문이 수정된 뒤 다시 요청하면") {
                then("다시 번역해 같은 행을 덮는다 — 생성 시각은 그대로, 원문 해시는 새 본문 기준") {
                    seed()
                    translate(visible)
                    val createdAt = scalar("SELECT created_at FROM content_translation")
                    exec("UPDATE food_review SET content = 'Edited: a bit salty' WHERE id = $visible")

                    val response = translate(visible)

                    body(response).path("payload").path("text").asText() shouldBe "[ko] Edited: a bit salty"
                    translator.calls.size shouldBe 2
                    rows() shouldBe 1L
                    scalar("SELECT created_at FROM content_translation") shouldBe createdAt
                    scalar("SELECT source_hash FROM content_translation") shouldBe sha256("${TranslationService.CACHE_VERSION}\nEdited: a bit salty")
                }
            }

            `when`("lang 이 지원하지 않는 값이면") {
                then("영어로 풀린 코드로 번역·저장한다 — lang 값마다 행·호출이 늘지 않는다") {
                    seed()

                    val first = translate(visible, lang = "xx")
                    translate(visible, lang = "zz")
                    translate(visible, lang = "en")

                    body(first).path("payload").path("language").asText() shouldBe "en"
                    translator.calls.map { it.second } shouldBe listOf(LanguageCode.EN)
                    rows() shouldBe 1L
                    scalar("SELECT language FROM content_translation") shouldBe "en"
                }
            }

            `when`("본문이 빈 리뷰면") {
                then("200 에 빈 문자열이고 번역 엔진을 부르지 않는다") {
                    seed()

                    val response = translate(emptyBody)

                    response.status shouldBe 200
                    body(response).path("payload").path("text").asText() shouldBe ""
                    translator.calls.size shouldBe 0
                    rows() shouldBe 0L
                }
            }
        }

        given("번역할 수 없는 리뷰") {
            `when`("삭제된 리뷰·삭제된 음식의 리뷰·내가 차단한 회원의 리뷰를 요청하면") {
                then("기존 리뷰 조회와 같은 REVIEW-001 이고 번역 엔진을 부르지 않는다") {
                    seed()

                    listOf(translate(deletedReview), translate(ofDeletedFood), translate(byBlockedAuthor, token = viewerToken())).forEach { response ->
                        response.status shouldBe 400
                        body(response).path("code").asText() shouldBe "REVIEW-001"
                    }
                    translate(999_999L).status shouldBe 400
                    translator.calls.size shouldBe 0
                    rows() shouldBe 0L
                }
            }

            `when`("차단하지 않은 사람(게스트)이 같은 리뷰를 요청하면") {
                then("번역된다 — 차단은 보는 사람 기준이다") {
                    seed()

                    translate(byBlockedAuthor).status shouldBe 200
                }
            }

            `when`("본인이 쓴 리뷰의 음식이 삭제됐으면") {
                then("번역된다 — 내 리뷰 목록에는 보이므로") {
                    seed()

                    translate(ownOfDeletedFood, token = viewerToken()).status shouldBe 200
                    translate(ownOfDeletedFood).status shouldBe 400
                }
            }

            `when`("전역 리뷰 피드에 보이는 남의 리뷰와 번역되는 남의 리뷰를 비교하면") {
                then("같은 집합이다 — 목록과 번역의 가시성 조건이 갈라지면 실패한다") {
                    seed()
                    val feed = body(
                        mockMvc.get("/api/reviews?lang=en") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${viewerToken()}")
                        }.andReturn().response,
                    ).path("payload").path("items").map { it.path("reviewId").asLong() }.toSet()

                    val translatable = othersReviews.filter { translate(it, token = viewerToken()).status == 200 }.toSet()

                    translatable shouldBe feed.intersect(othersReviews)
                    translatable shouldBe setOf(visible, emptyBody)
                }
            }
        }

        given("번역 엔진 실패") {
            `when`("엔진이 예외를 던지면") {
                then("503 TRANSLATION-001 이고 캐시 행이 없다 — 다음 요청이 다시 시도한다") {
                    seed()
                    translator.reply = { _, _ -> throw IllegalStateException("openai timeout") }

                    val response = translate(visible)

                    response.status shouldBe 503
                    body(response).path("code").asText() shouldBe "TRANSLATION-001"
                    rows() shouldBe 0L

                    translator.reset()
                    translate(visible).status shouldBe 200
                }
            }

            `when`("엔진이 빈 문자열이나 상한을 넘는 긴 출력을 돌려주면") {
                then("실패로 본다 — 503 이고 저장하지 않는다") {
                    seed()
                    translator.reply = { _, _ -> "  " }
                    translate(visible).status shouldBe 503
                    translator.reply = { _, _ -> "가".repeat(TranslationService.MAX_TRANSLATED_LENGTH + 1) }
                    val tooLong = translate(visible)

                    tooLong.status shouldBe 503
                    body(tooLong).path("code").asText() shouldBe "TRANSLATION-001"
                    rows() shouldBe 0L
                }
            }

            `when`("번역은 됐는데 캐시 저장이 실패하면") {
                then("번역문은 그대로 돌려준다 — 저장 실패가 요청을 깨지 않는다") {
                    seed()
                    val failingStore = java.lang.reflect.Proxy.newProxyInstance(
                        ContentTranslationJpaRepository::class.java.classLoader,
                        arrayOf(ContentTranslationJpaRepository::class.java),
                    ) { _, method, args ->
                        if (method.name == "upsert") throw org.springframework.dao.CannotAcquireLockException("테스트 — 저장 실패")
                        try {
                            method.invoke(translationRepository, *(args ?: emptyArray()))
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.targetException
                        }
                    } as ContentTranslationJpaRepository

                    val text = TranslationService(reviewService, failingStore, providerOf(translator), transactionManager, dataSource, 4)
                        .translate(null, TranslationTargetType.REVIEW, visible, LanguageCode.KO)

                    text shouldBe "[ko] The broth was rich and so tasty"
                    rows() shouldBe 0L
                }
            }
        }

        given("번역 엔진이 꺼져 있을 때(kbap.llm.translation.enabled=false — 엔진 빈 없음)") {
            `when`("번역을 요청하면") {
                then("503 TRANSLATION-001 — 스위치를 내려도 앱은 뜨고 번역만 안 된다") {
                    seed()
                    val service = TranslationService(reviewService, translationRepository, providerOf(null), transactionManager, dataSource, 4)

                    val error = io.kotest.assertions.throwables.shouldThrow<com.kbap.common.core.error.BusinessException> {
                        service.translate(null, TranslationTargetType.REVIEW, visible, LanguageCode.KO)
                    }

                    error.errorCode shouldBe com.kbap.common.core.error.ErrorCode.TRANSLATION_FAILED
                    rows() shouldBe 0L
                }
            }
        }

        given("동시 번역 엔진 호출 상한") {
            `when`("상한만큼 엔진 호출이 진행 중일 때 하나 더 오면") {
                then("기다리지 않고 503 TRANSLATION-001 — 엔진을 부르지 않는다. 진행 중이던 요청은 모두 200") {
                    seed()
                    val limit = translationService.maxConcurrentEngineCalls
                    val release = java.util.concurrent.CountDownLatch(1)
                    translator.reply = { text, target ->
                        release.await(30, TimeUnit.SECONDS)
                        "[${target.code}] $text"
                    }
                    val languages = listOf("ko", "ja", "vi", "th", "es", "ru", "id").take(limit)
                    val executor = Executors.newFixedThreadPool(limit)
                    val inFlight = languages.map { lang -> executor.submit(Callable { translate(visible, lang = lang).status }) }
                    val deadline = System.currentTimeMillis() + 10_000
                    while (translator.calls.size < limit && System.currentTimeMillis() < deadline) Thread.sleep(20)
                    translator.calls.size shouldBe limit

                    val overflow = translate(visible, lang = "en")

                    overflow.status shouldBe 503
                    body(overflow).path("code").asText() shouldBe "TRANSLATION-001"
                    translator.calls.size shouldBe limit
                    release.countDown()
                    inFlight.map { it.get(30, TimeUnit.SECONDS) } shouldBe List(limit) { 200 }
                    executor.shutdown()

                    translate(visible, lang = "en").status shouldBe 200
                }
            }

            `when`("설정한 상한이 풀의 절반보다 크면(운영에서 env 로 상한만 올린 경우)") {
                then("기동을 막지 않고 풀의 절반으로 낮춘다 — 번역이 풀을 다 쓰지 못한다") {
                    val poolSize = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java).maximumPoolSize

                    val service = TranslationService(reviewService, translationRepository, providerOf(translator), transactionManager, dataSource, 100)

                    service.maxConcurrentEngineCalls shouldBe poolSize / 2
                }
            }

            `when`("상한과 DB 커넥션 풀 크기를 비교하면") {
                then("상한은 풀의 절반 이하다 — 웹 요청은 엔진 호출 동안에도 커넥션 하나를 쥐고 있어(open-in-view) 번역이 풀을 다 쓰면 안 된다") {
                    val poolSize = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java).maximumPoolSize

                    (translationService.maxConcurrentEngineCalls * 2 <= poolSize) shouldBe true
                }
            }
        }

        given("번역 호출과 트랜잭션·동시성") {
            `when`("번역 엔진을 부르는 동안") {
                then("DB 트랜잭션이 열려 있지 않다 — 느린 외부 호출이 트랜잭션·행 잠금을 쥐지 않는다") {
                    seed()

                    translate(visible, token = viewerToken())

                    translator.transactionActiveDuringCalls shouldBe listOf(false)
                }
            }

            `when`("같은 리뷰·언어를 두 요청이 동시에 번역하면") {
                then("둘 다 200 이고 캐시 행은 하나다") {
                    seed()
                    val bothInEngine = CyclicBarrier(2)
                    translator.reply = { text, target ->
                        bothInEngine.await(10, TimeUnit.SECONDS)
                        "[${target.code}] $text"
                    }
                    val executor = Executors.newFixedThreadPool(2)

                    val statuses = executor.invokeAll(List(2) { Callable { translate(visible).status } }).map { it.get(30, TimeUnit.SECONDS) }
                    executor.shutdown()

                    statuses shouldBe listOf(200, 200)
                    translator.calls.size shouldBe 2
                    rows() shouldBe 1L
                }
            }
        }

        given("요청 검증과 인증") {
            `when`("모르는 targetType·lang 누락·targetId 누락이면") {
                then("400 COMMON-002 이고 번역 엔진을 부르지 않는다") {
                    seed()

                    val unknownType = translate(visible, targetType = "POST")
                    val missingLang = translate(visible, lang = null)
                    val missingId = mockMvc.post("/api/translations?lang=ko") {
                        header("X-API-Version", "1.0")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"targetType":"REVIEW"}"""
                    }.andReturn().response

                    listOf(unknownType, missingLang, missingId).forEach { response ->
                        response.status shouldBe 400
                        body(response).path("code").asText() shouldBe "COMMON-002"
                    }
                    translator.calls.size shouldBe 0
                }
            }

            `when`("위조된 토큰을 보내면") {
                then("401 — 토큰은 선택이지만 보냈으면 검증한다") {
                    seed()

                    translate(visible, token = "not-a-jwt").status shouldBe 401
                }
            }
        }

        given("api-docs") {
            `when`("문서를 보면") {
                then("번역 경로와 503 TRANSLATION-001 이 실려 있다") {
                    val operation = mapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8))
                        .path("paths").path("/api/translations").path("post")

                    operation.path("responses").has("503") shouldBe true
                    operation.path("description").asText().contains("TRANSLATION-001") shouldBe true
                }
            }
        }
    }
}
