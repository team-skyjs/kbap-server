package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodImage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class FoodDetailImagesTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var foodImageRepository: FoodImageJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun seedFood(koreanName: String, imageRef: String?): Long =
            foodRepository.save(Food(koreanName = koreanName, description = "설명", imageRef = imageRef)).id

        fun seedImage(foodId: Long, key: String, isPrimary: Boolean, sortOrder: Int) {
            foodImageRepository.save(
                FoodImage(foodId = foodId, imageKey = key, isPrimary = isPrimary, sortOrder = sortOrder),
            )
        }

        given("음식 상세의 이미지 갤러리") {
            `when`("대표 1장과 추가 2장이 있는 음식을 조회하면") {
                then("대표(imageRef 와 같은 URL)가 먼저 오고 나머지는 정렬 순서대로 내려간다") {
                    val foodId = seedFood("갤러리불고기", "images/webp/bulgogi.webp")
                    seedImage(foodId, "images/webp/bulgogi-2.webp", isPrimary = false, sortOrder = 1)
                    seedImage(foodId, "images/webp/bulgogi.webp", isPrimary = true, sortOrder = 0)
                    seedImage(foodId, "images/webp/bulgogi-3.webp", isPrimary = false, sortOrder = 2)

                    mockMvc.get("/api/foods/$foodId?lang=ko").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.images.length()") { value(3) }
                        jsonPath("$.payload.imageRef") { value("https://cdn.test/images/webp/bulgogi.webp") }
                        jsonPath("$.payload.images[0].url") { value("https://cdn.test/images/webp/bulgogi.webp") }
                        jsonPath("$.payload.images[1].url") { value("https://cdn.test/images/webp/bulgogi-2.webp") }
                        jsonPath("$.payload.images[2].url") { value("https://cdn.test/images/webp/bulgogi-3.webp") }
                    }
                }
            }

            `when`("갤러리 행이 없는 음식을 조회하면") {
                then("images 는 빈 배열이고 기존 imageRef 는 그대로 내려간다") {
                    val foodId = seedFood("갤러리없음음식", "images/webp/none.webp")

                    mockMvc.get("/api/foods/$foodId?lang=ko").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.images.length()") { value(0) }
                        jsonPath("$.payload.imageRef") { value("https://cdn.test/images/webp/none.webp") }
                    }
                }
            }

            `when`("소프트 삭제된 이미지가 섞여 있으면") {
                then("살아 있는 이미지만 내려간다") {
                    val foodId = seedFood("삭제섞인음식", "images/webp/kept.webp")
                    seedImage(foodId, "images/webp/kept.webp", isPrimary = true, sortOrder = 0)
                    val removed = foodImageRepository.save(
                        FoodImage(foodId = foodId, imageKey = "images/webp/removed.webp", sortOrder = 1),
                    )
                    removed.delete()
                    foodImageRepository.save(removed)

                    mockMvc.get("/api/foods/$foodId?lang=ko").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.images.length()") { value(1) }
                        jsonPath("$.payload.images[0].url") { value("https://cdn.test/images/webp/kept.webp") }
                    }
                }
            }
        }
    }
}
