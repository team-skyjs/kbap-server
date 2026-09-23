package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodViewLogJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.lang.reflect.Proxy
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@IntegrationTest
class FoodViewLogTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun seedFood(koreanName: String): Long =
            foodRepository.save(Food(koreanName = koreanName, description = "설명")).id

        fun accessToken(memberId: Long): String {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO member (id, provider, provider_uid, member_status, " +
                            "onboarding_completed, status, created_at, updated_at) " +
                            "VALUES ($memberId, 'GOOGLE', 'food-view-log-$memberId', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) " +
                            "ON DUPLICATE KEY UPDATE id = id",
                    )
                }
            }
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun countByFood(foodId: Long, memberId: Long?): Int =
            dataSource.connection.use { c ->
                val memberClause = if (memberId == null) "member_id IS NULL" else "member_id = $memberId"
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM food_view_log WHERE food_id = $foodId AND $memberClause").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        fun view(foodId: Long, token: String? = null) =
            mockMvc.get("/api/foods/$foodId?lang=ko") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        given("음식 상세 조회 이력") {
            `when`("회원이 상세를 조회하면") {
                then("잠시 뒤 그 회원의 조회 이력 1행이 남는다") {
                    val foodId = seedFood("회원조회음식")
                    val token = accessToken(7101L)

                    view(foodId, token).andExpect { status { isOk() } }

                    eventually(5.seconds) { countByFood(foodId, 7101L) shouldBe 1 }
                }
            }

            `when`("게스트가 상세를 조회하면") {
                then("member_id 가 비어 있는 이력 1행이 남는다") {
                    val foodId = seedFood("게스트조회음식")

                    view(foodId).andExpect { status { isOk() } }

                    eventually(5.seconds) { countByFood(foodId, null) shouldBe 1 }
                }
            }

            `when`("같은 회원이 3번 조회하면") {
                then("이력 3행이 남는다") {
                    val foodId = seedFood("반복조회음식")
                    val token = accessToken(7102L)

                    repeat(3) { view(foodId, token).andExpect { status { isOk() } } }

                    eventually(5.seconds) { countByFood(foodId, 7102L) shouldBe 3 }
                }
            }

            `when`("없는 음식을 조회해 실패 응답을 받으면") {
                then("이력이 남지 않는다") {
                    val missingFoodId = 987654321L

                    view(missingFoodId).andExpect { status { isBadRequest() } }

                    continually(1.seconds) { countByFood(missingFoodId, null) shouldBe 0 }
                }
            }
        }

        given("조회 이력 저장이 실패하는 상황") {
            `when`("리스너의 저장이 예외를 던지면") {
                then("예외가 전파되지 않는다") {
                    val failingRepository = Proxy.newProxyInstance(
                        FoodViewLogJpaRepository::class.java.classLoader,
                        arrayOf(FoodViewLogJpaRepository::class.java),
                    ) { _, _, _ -> throw IllegalStateException("db down") } as FoodViewLogJpaRepository
                    val listener = FoodViewLogListener(failingRepository)

                    shouldNotThrowAny { listener.handle(FoodViewed(foodId = 1L, memberId = null)) }
                }
            }
        }
    }
}
