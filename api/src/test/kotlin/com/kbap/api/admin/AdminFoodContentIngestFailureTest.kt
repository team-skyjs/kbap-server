package com.kbap.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.failedBody
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodContentIngestFailureTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val namePrefix = "적재실패-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(rawName: String, contentStatus: FoodContentStatus): Food =
            foodJpaRepository.save(
                Food(
                    koreanName = namePrefix + rawName,
                    displayName = namePrefix + rawName,
                    imageRef = "images/food/$rawName.webp",
                    description = "구수한 $rawName",
                    spiciness = 3,
                    ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                    contentStatus = contentStatus,
                ),
            )

        fun ingest(body: Map<String, Any?>): ResultActionsDsl =
            mockMvc.post(PATH) {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }

        fun reloaded(id: Long): Food = foodJpaRepository.findById(id).orElseThrow()

        given("실패 결과 적재") {
            `when`("이미 서비스 중인 음식이면") {
                then("콘텐츠·상태·사진을 보존하고 실패 기록만 남긴다") {
                    clearFoods()
                    val food = saveFood("칼국수", FoodContentStatus.READY)

                    ingest(failedBody(food.id, failureKind = "INGREDIENT_GUARD", reason = "기피성분 62점 < 임계값 80"))
                        .andExpect { status { isOk() } }

                    val updated = reloaded(food.id)
                    updated.contentStatus shouldBe FoodContentStatus.READY
                    updated.description shouldBe "구수한 칼국수"
                    updated.ingredients?.map { it.code } shouldBe listOf("SOYBEAN")
                    updated.imageRef shouldBe "images/food/칼국수.webp"
                    updated.contentFailureKind shouldBe FoodContentFailureKind.INGREDIENT_GUARD
                    updated.contentReviewRejectionReason shouldBe "기피성분 62점 < 임계값 80"
                }
            }

            `when`("서비스 중이 아닌 음식이면") {
                then("관리자 확인 상태로 내려가고 사유가 기록된다") {
                    clearFoods()
                    val food = saveFood("콩국수", FoodContentStatus.PENDING_IMAGE)

                    ingest(failedBody(food.id, failureKind = "NOT_FOOD", reason = "콘텐츠 생성 부적합: 비음식"))
                        .andExpect { status { isOk() } }

                    val updated = reloaded(food.id)
                    updated.contentStatus shouldBe FoodContentStatus.FAILED
                    updated.contentFailureKind shouldBe FoodContentFailureKind.NOT_FOOD
                    updated.contentReviewAttempts shouldBe 1
                }
            }
        }
    }
}
