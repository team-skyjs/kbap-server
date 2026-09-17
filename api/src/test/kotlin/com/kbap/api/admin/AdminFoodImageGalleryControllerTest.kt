package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.food.FoodImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodImage
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class AdminFoodImageGalleryControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var foodImageRepository: FoodImageJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: com.kbap.common.domain.food.FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun clear(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_image")
                    it.execute("DELETE FROM food")
                }
            }

        beforeContainer { clear() }
        afterSpec { clear() }

        fun adminToken(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun saveFood(name: String, imageRef: String?, status: FoodContentStatus = FoodContentStatus.READY): Food =
            foodRepository.save(
                Food(koreanName = name, description = "설명", imageRef = imageRef, contentStatus = status),
            )

        fun saveImage(foodId: Long, key: String, isPrimary: Boolean, sortOrder: Int): FoodImage =
            foodImageRepository.save(
                FoodImage(foodId = foodId, imageKey = key, isPrimary = isPrimary, sortOrder = sortOrder),
            )

        fun gallery(foodId: Long, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.get("/api/admin/foods/$foodId/images") {
                token?.let { header("Authorization", "Bearer $it") }
            }

        fun setPrimary(foodId: Long, imageId: Long, version: Long, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.put("/api/admin/foods/$foodId/images/$imageId/primary") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("version" to version))
            }

        fun payloadOf(result: ResultActionsDsl) =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("어드민 이미지 갤러리 조회") {
            `when`("대표 1장과 추가 2장이 있는 음식을 조회하면") {
                then("대표가 먼저, 이후 정렬 순서로 내려가고 version·contentStatus 가 함께 온다") {
                    val food = saveFood("갤러리불고기", "images/webp/a.webp")
                    saveImage(food.id, "images/webp/b.webp", isPrimary = false, sortOrder = 2)
                    saveImage(food.id, "images/webp/a.webp", isPrimary = true, sortOrder = 0)
                    saveImage(food.id, "images/webp/c.webp", isPrimary = false, sortOrder = 1)

                    val payload = payloadOf(gallery(food.id))
                    payload.path("foodId").asLong() shouldBe food.id
                    payload.path("contentStatus").asText() shouldBe "READY"
                    payload.path("version").asLong() shouldBe food.version
                    payload.path("items").size() shouldBe 3
                    payload.path("items")[0].path("imageKey").asText() shouldBe "images/webp/a.webp"
                    payload.path("items")[0].path("isPrimary").asBoolean() shouldBe true
                    payload.path("items")[0].path("imageUrl").asText() shouldBe "https://cdn.test/images/webp/a.webp"
                    payload.path("items")[1].path("imageKey").asText() shouldBe "images/webp/c.webp"
                    payload.path("items")[2].path("imageKey").asText() shouldBe "images/webp/b.webp"
                }
            }

            `when`("이미지가 없는 음식을 조회하면") {
                then("items 는 빈 배열이다") {
                    val food = saveFood("이미지없음", null)

                    payloadOf(gallery(food.id)).path("items").size() shouldBe 0
                }
            }

            `when`("어드민 토큰 없이 조회하면") {
                then("401 로 거절한다") {
                    val food = saveFood("무토큰음식", null)

                    gallery(food.id, token = null).andExpect { status { isUnauthorized() } }
                }
            }
        }

        fun updateFood(food: Food, imageRef: String?, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.put("/api/admin/foods/${food.id}") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(
                    mapOf(
                        "koreanName" to food.koreanName,
                        "displayName" to food.displayName,
                        "description" to food.description,
                        "spiciness" to food.spiciness,
                        "contentStatus" to food.contentStatus.name,
                        "imageRef" to imageRef,
                        "version" to food.version,
                    ),
                )
            }

        given("음식 수정의 imageRef 차단") {
            `when`("조회값과 같은 imageRef 를 그대로 되돌려 보내면") {
                then("통과한다 — 수정 폼이 깨지지 않는다") {
                    val food = saveFood("동일imageRef음식", "images/webp/same.webp")

                    updateFood(food, "images/webp/same.webp").andExpect { status { isOk() } }
                }
            }

            `when`("다른 imageRef 로 바꾸려 하면") {
                then("400 FOOD-012 로 거절한다 — 대표 지정 API 가 유일한 경로다") {
                    val food = saveFood("다른imageRef음식", "images/webp/keep.webp")

                    updateFood(food, "images/webp/hijack.webp").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-012") }
                    }

                    foodRepository.findById(food.id).orElseThrow().imageRef shouldBe "images/webp/keep.webp"
                }
            }
        }

        given("대표 이미지 교체") {
            `when`("다른 이미지를 대표로 지정하면") {
                then("대표가 바뀌고 food.image_ref 도 그 키로 갱신된다") {
                    val food = saveFood("대표교체음식", "images/webp/old.webp")
                    saveImage(food.id, "images/webp/old.webp", isPrimary = true, sortOrder = 0)
                    val next = saveImage(food.id, "images/webp/new.webp", isPrimary = false, sortOrder = 1)

                    val payload = payloadOf(setPrimary(food.id, next.id, food.version))
                    payload.path("items")[0].path("imageKey").asText() shouldBe "images/webp/new.webp"
                    payload.path("items")[0].path("isPrimary").asBoolean() shouldBe true

                    foodRepository.findById(food.id).orElseThrow().imageRef shouldBe "images/webp/new.webp"
                }
            }

            `when`("이미 대표인 이미지를 다시 지정하면") {
                then("멱등하게 200 이다") {
                    val food = saveFood("멱등음식", "images/webp/p.webp")
                    val primary = saveImage(food.id, "images/webp/p.webp", isPrimary = true, sortOrder = 0)

                    setPrimary(food.id, primary.id, food.version).andExpect { status { isOk() } }
                }
            }

            `when`("이미 대표인 이미지를 다시 지정해도") {
                then("벡터 아웃박스가 쌓이지 않는다 — 바뀐 게 없으면 재색인도 없다") {
                    val food = saveFood("멱등아웃박스음식", "images/webp/idem.webp")
                    val primary = saveImage(food.id, "images/webp/idem.webp", isPrimary = true, sortOrder = 0)

                    setPrimary(food.id, primary.id, food.version).andExpect { status { isOk() } }

                    vectorOutboxRepository.findAll().count { it.foodId == food.id } shouldBe 0
                }
            }

            `when`("version 이 최신이 아니면") {
                then("409 FOOD-006 으로 거절한다") {
                    val food = saveFood("버전충돌음식", "images/webp/v.webp")
                    val image = saveImage(food.id, "images/webp/v2.webp", isPrimary = false, sortOrder = 1)

                    setPrimary(food.id, image.id, food.version + 5).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-006") }
                    }
                }
            }

            `when`("다른 음식의 이미지 id 를 지정하면") {
                then("404 로 거절한다") {
                    val food = saveFood("주인아님음식", "images/webp/x.webp")
                    val other = saveFood("다른음식", "images/webp/y.webp")
                    val otherImage = saveImage(other.id, "images/webp/y.webp", isPrimary = true, sortOrder = 0)

                    setPrimary(food.id, otherImage.id, food.version).andExpect { status { isNotFound() } }
                }
            }
        }
    }
}
