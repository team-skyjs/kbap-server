package com.kbap.api.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.translation.TranslationService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.security.MessageDigest
import javax.sql.DataSource

@IntegrationTest
class ReviewFeedTranslationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    private val mapper = jacksonObjectMapper()

    init {
        val author = 94001L
        val food = 9400L

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun hashOf(source: String) = MessageDigest.getInstance("SHA-256")
            .digest("${TranslationService.CACHE_VERSION}\n$source".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun review(id: Long, content: String?, language: String?) = exec(
            "INSERT INTO food_review (id, member_id, food_id, rating, content, language, status, created_at, updated_at) " +
                "VALUES ($id, $author, $food, 5, ${q(content)}, ${q(language)}, 'ACTIVE', NOW(6), NOW(6))",
        )
        fun stored(reviewId: Long, lang: String, sourceForHash: String, text: String, sourceLanguage: String?) = exec(
            "INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, source_language, status, created_at, updated_at) " +
                "VALUES ('REVIEW', $reviewId, ${q(lang)}, ${q(hashOf(sourceForHash))}, ${q(text)}, ${q(sourceLanguage)}, 'ACTIVE', NOW(6), NOW(6))",
        )
        fun feed(query: String): JsonNode = mapper.readTree(
            mockMvc.get("/api/reviews?$query") { header("X-API-Version", "1.0") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun byId(page: JsonNode) = page.path("items").associateBy { it.path("reviewId").asLong() }

        beforeEach {
            TestTables.clearAll(dataSource)
            exec(
                "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, review_count, unique_reviewed_food_count, created_at, updated_at) " +
                    "VALUES ($author, 'GOOGLE', 'feed-translation-$author', 'ACTIVE', 1, 'ACTIVE', 0, 0, NOW(6), NOW(6))",
            )
            exec(
                "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                    "VALUES ($food, '피드번역음식', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
        }

        given("전역 리뷰 피드 첫 페이지의 저장된 번역(KB-940)") {
            `when`("요청 언어와 다른 리뷰에 지금 원문과 맞는 저장 번역이 있으면") {
                then("그 항목에만 translation{text, sourceLanguage} 이 붙고, 원문이 바뀌었거나 같은 언어거나 저장본이 없으면 필드가 없다") {
                    review(1, "The broth was rich and so tasty", "en")
                    stored(1, "ko", "The broth was rich and so tasty", "국물이 진하고 정말 맛있어요", "en")
                    review(2, "Edited: a bit salty now", "en")
                    stored(2, "ko", "Original body before edit", "수정 전 번역", "en")
                    review(3, "정말 맛있어요", "ko")
                    stored(3, "ko", "정말 맛있어요", "정말 맛있어요", null)
                    review(4, "Nice and spicy noodles", "en")
                    review(5, "Best bibimbap ever lol", null)
                    stored(5, "ko", "Best bibimbap ever lol", "인생 비빔밥 ㅋㅋ", "en")
                    stored(1, "ja", "The broth was rich and so tasty", "スープが濃厚", "en")

                    val items = byId(feed("lang=ko"))

                    items.getValue(1).path("translation").let {
                        it.path("text").asText() shouldBe "국물이 진하고 정말 맛있어요"
                        it.path("sourceLanguage").asText() shouldBe "en"
                    }
                    items.getValue(5).path("translation").path("text").asText() shouldBe "인생 비빔밥 ㅋㅋ"
                    listOf(2L, 3L, 4L).forEach { id -> items.getValue(id).has("translation") shouldBe false }
                }
            }

            `when`("다음 페이지(cursor)이거나 음식별 목록이면") {
                then("저장 번역이 있어도 필드가 없다") {
                    (1L..55L).forEach { id ->
                        review(id, "English review number $id is tasty", "en")
                        stored(id, "ko", "English review number $id is tasty", "번역 $id", "en")
                    }

                    val first = feed("lang=ko")
                    first.path("items").all { it.has("translation") } shouldBe true
                    val second = feed("lang=ko&cursor=${first.path("nextCursor").asText()}")
                    (second.path("items").size() > 0) shouldBe true
                    second.path("items").none { it.has("translation") } shouldBe true
                    feed("lang=ko&foodId=$food").path("items").none { it.has("translation") } shouldBe true
                }
            }

            `when`("저장 번역이 붙는 항목 수가 늘어나도") {
                then("첫 페이지 조회의 SQL 문 수는 같다") {
                    (1L..20L).forEach { id -> review(id, "Query count review $id", "en") }
                    stored(1, "ko", "Query count review 1", "번역 1", "en")

                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    val originallyEnabled = statistics.isStatisticsEnabled
                    statistics.isStatisticsEnabled = true
                    try {
                        fun statementsForFirstPage(): Long {
                            statistics.clear()
                            feed("lang=ko")
                            return statistics.prepareStatementCount
                        }
                        val withOne = statementsForFirstPage()
                        (2L..20L).forEach { id -> stored(id, "ko", "Query count review $id", "번역 $id", "en") }
                        val withTwenty = statementsForFirstPage()

                        withTwenty shouldBe withOne
                        byId(feed("lang=ko")).values.count { it.has("translation") } shouldBe 20
                    } finally {
                        statistics.isStatisticsEnabled = originallyEnabled
                    }
                }
            }
        }
    }
}
