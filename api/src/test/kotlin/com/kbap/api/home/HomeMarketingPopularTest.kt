package com.kbap.api.home

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.food.FoodService
import com.kbap.api.food.PopularFoodIdCache
import com.kbap.api.ingredient.IngredientService
import com.kbap.api.member.MemberService
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDateTime
import java.time.ZoneId
import javax.sql.DataSource

@IntegrationTest
class HomeMarketingPopularTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var ingredientService: IngredientService

    @Autowired
    private lateinit var foodService: FoodService

    @Autowired
    private lateinit var bookmarkService: BookmarkService

    @Autowired
    private lateinit var reviewService: ReviewService

    @Autowired
    private lateinit var popularFoodIdCache: PopularFoodIdCache

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        val curatedIds = listOf(3L, 1L, 999L, 2L)
        val active = HomeMarketingPopularProperties(startsAt = now.minusDays(1), endsAt = now.plusDays(1), foodIds = curatedIds)
        val ended = HomeMarketingPopularProperties(startsAt = now.minusDays(2), endsAt = now.minusDays(1), foodIds = curatedIds)

        fun homeService(properties: HomeMarketingPopularProperties) = HomeService(
            memberService,
            ingredientService,
            foodService,
            bookmarkService,
            reviewService,
            popularFoodIdCache,
            properties,
            transactionManager,
        )

        fun popular(properties: HomeMarketingPopularProperties, memberId: Long? = null, lang: LanguageCode = LanguageCode.EN) =
            homeService(properties).getHome(memberId, lang).popularFoods

        fun popularIds(properties: HomeMarketingPopularProperties, memberId: Long? = null) =
            popular(properties, memberId).map { it.foodId }

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun seedExtraViews(foodId: Long, count: Int) = repeat(count) {
            execute(
                "INSERT INTO food_view_log (food_id, member_id, status, created_at, updated_at) " +
                    "VALUES ($foodId, NULL, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            )
        }

        beforeContainer {
            HomeTestSeed.reset(dataSource)
            popularFoodIdCache.invalidateAll()
        }

        given("마케팅 기간 안의 홈") {
            `when`("음식 1·2·3 이 READY 로 있으면") {
                then("popularFoods 는 설정 순서 3, 1, 2 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    popularIds(active) shouldContainExactly listOf(3L, 1L, 2L)
                }
            }

            `when`("기피 성분이 EGG 인 회원이 조회하면") {
                then("음식 1 은 DANGER 이고 목록 순서는 그대로다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    HomeTestSeed.seedFoodSubstance(dataSource, foodId = 1L, code = "EGG", percent = 100)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    val foods = popular(active, memberId = 11L)

                    foods.map { it.foodId } shouldContainExactly listOf(3L, 1L, 2L)
                    foods.single { it.foodId == 1L }.overallRiskStatus shouldBe RiskLevel.DANGER
                }
            }

            `when`("ja 로 조회하면") {
                then("첫 항목 이름이 メニュー3 이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)

                    popular(active, lang = LanguageCode.JA).first().name shouldBe "メニュー3"
                }
            }
        }

        given("마케팅 기간 밖의 홈") {
            `when`("적용 기간이 끝난 설정이면") {
                then("popularFoods 는 조회 수 집계 결과다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    popularIds(ended) shouldContainExactly listOf(2L, 3L, 1L)
                }
            }

            `when`("홈 API 를 호출하면") {
                then("공유 컨텍스트 설정은 비활성이라 조회 수 집계가 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    val body = mockMvc.get("/api/home?lang=en").andReturn().response.getContentAsString(Charsets.UTF_8)
                    val ids = jacksonObjectMapper().readTree(body).path("payload").path("popularFoods").map { it.path("foodId").asLong() }

                    ids shouldContainExactly listOf(2L, 3L, 1L)
                }
            }
        }

        given("마케팅 목록의 노출 불가 음식") {
            `when`("음식 1 이 READY 가 아니면") {
                then("popularFoods 는 3, 2 이고 빈자리를 채우지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET content_status = 'FAILED' WHERE id = 1")

                    popularIds(active) shouldContainExactly listOf(3L, 2L)
                }
            }

            `when`("음식 1 이 삭제되면") {
                then("popularFoods 는 3, 2 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET status = 'DELETED' WHERE id = 1")

                    popularIds(active) shouldContainExactly listOf(3L, 2L)
                }
            }

            `when`("목록의 음식이 전부 READY 가 아니면") {
                then("popularFoods 는 빈 목록이고 조회 수 집계로 폴백하지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 4)
                    execute("UPDATE food SET content_status = 'FAILED' WHERE id IN (1, 2, 3)")

                    popularIds(active).shouldBeEmpty()
                    popularIds(ended) shouldContainExactly listOf(4L)
                }
            }
        }
    }
}
