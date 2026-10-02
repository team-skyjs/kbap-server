package com.kbap.api.review

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@IntegrationTest
class ReviewLanguageBackfillTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var detector: ReviewLanguageDetector

    init {
        fun backfillOf(enabled: Boolean = true) = ReviewLanguageBackfill(jdbcTemplate, detector, enabled)

        fun seed() {
            TestTables.clearAll(dataSource)
            jdbcTemplate.update(
                "INSERT INTO member (id, provider, provider_uid, nickname, member_status, onboarding_completed, status, created_at, updated_at) " +
                    "VALUES (900, 'GOOGLE', 'backfill-900', '채우기', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))",
            )
            jdbcTemplate.update(
                "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, ingredients, " +
                    "content_status, status, created_at, updated_at) VALUES (900, '채우기음식', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
            listOf(
                Triple(1L, "국물이 진하고 정말 맛있어요", "ACTIVE" to null),
                Triple(2L, "The broth was rich and so tasty", "ACTIVE" to null),
                Triple(3L, "ㅋㅋㅋ", "ACTIVE" to null),
                Triple(4L, null, "ACTIVE" to null),
                Triple(5L, "삭제된 리뷰도 한국어예요", "DELETED" to null),
                Triple(6L, "이미 값이 있는 리뷰는 건드리지 않아요", "ACTIVE" to "ja"),
                Triple(7L, "湯頭濃郁，非常好吃", "ACTIVE" to null),
            ).forEach { (id, content, statusAndLanguage) ->
                jdbcTemplate.update(
                    "INSERT INTO food_review (id, member_id, food_id, rating, content, language, status, version, created_at, updated_at) " +
                        "VALUES (?, 900, 900, 5, ?, ?, ?, 3, '2026-09-01 00:00:00', '2026-09-02 00:00:00')",
                    id, content, statusAndLanguage.second, statusAndLanguage.first,
                )
            }
        }

        fun languages(): Map<Long, String?> =
            jdbcTemplate.query("SELECT id, language FROM food_review ORDER BY id") { rs, _ -> rs.getLong(1) to rs.getString(2) }.toMap()

        fun untouchedColumns(): List<String> =
            jdbcTemplate.query("SELECT CONCAT_WS('|', id, IFNULL(content, ''), status, version, updated_at, created_at) FROM food_review ORDER BY id") { rs, _ -> rs.getString(1) }

        given("기존 리뷰의 본문 언어 채우기") {
            `when`("언어가 비어 있는 리뷰가 있으면") {
                then("판별되는 것만 채운다 — 애매한 글·본문 없는 리뷰·삭제된 리뷰·이미 값이 있는 리뷰는 그대로다") {
                    seed()
                    val before = untouchedColumns()

                    val result = backfillOf().backfill()

                    languages() shouldBe mapOf(1L to "ko", 2L to "en", 3L to null, 4L to null, 5L to null, 6L to "ja", 7L to "zh-Hant")
                    result shouldBe ReviewLanguageBackfillResult(examined = 4, filled = 3)
                    result.leftUnknown shouldBe 1
                    untouchedColumns() shouldBe before
                }
            }

            `when`("다시 돌리면") {
                then("같은 결과다 — 채운 값은 다시 쓰지 않고, 애매한 글만 다시 살펴본다") {
                    seed()
                    backfillOf().backfill()
                    val afterFirst = languages()

                    val second = backfillOf().backfill()

                    languages() shouldBe afterFirst
                    second shouldBe ReviewLanguageBackfillResult(examined = 1, filled = 0)
                }
            }

            `when`("살펴보는 사이에 다른 요청이 그 리뷰의 언어를 먼저 채웠으면") {
                then("덮어쓰지 않는다") {
                    seed()
                    val filledMeanwhile = object : ReviewLanguageDetector() {
                        override fun detect(content: String?): LanguageCode? {
                            jdbcTemplate.update("UPDATE food_review SET language = 'en' WHERE id = 1 AND language IS NULL")
                            return super.detect(content)
                        }
                    }

                    val result = ReviewLanguageBackfill(jdbcTemplate, filledMeanwhile, true).backfill()

                    languages()[1L] shouldBe "en"
                    result shouldBe ReviewLanguageBackfillResult(examined = 4, filled = 2)
                }
            }

            `when`("살펴보는 사이에 작성자가 본문을 고쳤으면") {
                then("옛 본문으로 판별한 언어를 쓰지 않는다 — 수정이 정한 값(애매해서 null)이 남는다") {
                    seed()
                    val editedMeanwhile = object : ReviewLanguageDetector() {
                        override fun detect(content: String?): LanguageCode? {
                            jdbcTemplate.update("UPDATE food_review SET content = 'ㅋㅋㅋ', version = version + 1 WHERE id = 1 AND version = 3")
                            return super.detect(content)
                        }
                    }

                    ReviewLanguageBackfill(jdbcTemplate, editedMeanwhile, true).backfill()

                    languages()[1L] shouldBe null
                    languages()[2L] shouldBe "en"
                }
            }

            `when`("스위치를 끄면") {
                then("기동 뒤에도 돌지 않는다") {
                    seed()

                    backfillOf(enabled = false).onApplicationReady() shouldBe null

                    languages()[1L] shouldBe null
                }
            }

            `when`("스위치가 켜져 있으면") {
                then("기동 뒤 별도 스레드에서 한 번 돈다 — 기동을 붙잡지 않는다") {
                    seed()

                    val thread = backfillOf().onApplicationReady()

                    (thread != null) shouldBe true
                    thread!!.isDaemon shouldBe true
                    thread.join(10_000)
                    languages()[1L] shouldBe "ko"
                }
            }
        }
    }
}
