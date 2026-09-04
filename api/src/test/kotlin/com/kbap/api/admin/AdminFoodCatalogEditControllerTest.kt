package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@IntegrationTest
class AdminFoodCatalogEditControllerTest : AdminFoodCatalogTestSupport() {
    init {
        given("어드민 음식 수정 API") {
            `when`("전체 필드를 보내 수정하면") {
                then("반영된 상세를 내려주고 DB 에도 저장된다") {
                    val food = saveFood("수정전찌개")

                    putUpdate(
                        food.id,
                        mapOf(
                            "koreanName" to "수정후찌개",
                            "description" to "더 구수한 찌개",
                            "spiciness" to 4,
                            "contentStatus" to "PENDING_REVIEW",
                            "imageRef" to "images/food/updated.webp",
                            "nameTranslations" to mapOf("en" to "Updated stew"),
                            "descriptionTranslations" to mapOf("en" to "richer stew"),
                            "ingredients" to listOf(mapOf("code" to "SOY", "inclusion_percent" to 80)),
                            "version" to food.version,
                        ),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.koreanName") { value("수정후찌개") }
                        jsonPath("$.payload.description") { value("더 구수한 찌개") }
                        jsonPath("$.payload.spiciness") { value(4) }
                        jsonPath("$.payload.contentStatus") { value("PENDING_REVIEW") }
                        jsonPath("$.payload.nameTranslations.en") { value("Updated stew") }
                        jsonPath("$.payload.ingredients[0].inclusion_percent") { value(80) }
                    }

                    val reloaded = foodJpaRepository.findById(food.id).orElseThrow()
                    reloaded.displayName shouldBe "수정후찌개"
                    reloaded.spiciness shouldBe 4
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }

            `when`("다른 음식과 같은 이름으로 수정하면") {
                then("409(FOOD-005) 로 거절한다") {
                    saveFood("김치찌개")
                    val food = saveFood("된장찌개")

                    putUpdate(food.id, updateBody(koreanName = "김치찌개")).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-005") }
                    }
                }
            }

            `when`("이름을 비워 보내면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("된장찌개")

                    putUpdate(food.id, updateBody(koreanName = " ")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("없는 id 를 수정하면") {
                then("400(FOOD-001) 로 거절한다") {
                    putUpdate(999999, updateBody(koreanName = "유령찌개")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("검수를 거치지 않은 음식을 READY 로 직접 전이하려 하면") {
                then("400(FOOD-010) 로 거절한다 — READY 전이는 검수 승인 API 몫") {
                    val food = saveFood("직행찌개", FoodContentStatus.FAILED)

                    putUpdate(food.id, updateBody(koreanName = "직행찌개")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-010") }
                    }

                    foodJpaRepository.findById(food.id).orElseThrow().contentStatus shouldBe FoodContentStatus.FAILED
                }
            }

            `when`("이미 READY 인 음식을 READY 그대로 수정하면") {
                then("멱등하게 성공한다") {
                    val food = saveFood("유지찌개")

                    putUpdate(food.id, updateBody(koreanName = "유지찌개")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.contentStatus") { value("READY") }
                    }
                }
            }

            `when`("READY 음식을 FAILED 로 내리면") {
                then("허용된다 — 잘못된 콘텐츠 강제 회수 경로") {
                    val food = saveFood("회수찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "회수찌개") + mapOf("contentStatus" to "FAILED"),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.contentStatus") { value("FAILED") }
                    }
                }
            }

            `when`("표시 이름을 따로 주고 수정하면") {
                then("매치키(koreanName)와 분리된 표시 이름이 저장되고 검색에도 걸린다") {
                    val food = saveFood("김치찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "김치찌개") + mapOf("displayName" to "김치찌개 (2인분)"),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.displayName") { value("김치찌개 (2인분)") }
                        jsonPath("$.payload.koreanName") { value("김치찌개 (2인분)") }
                        jsonPath("$.payload.matchKey") { value("김치찌개") }
                    }

                    foodJpaRepository.findById(food.id).orElseThrow().displayName shouldBe "김치찌개 (2인분)"
                    getList("?q=2인분").andExpect { jsonPath("$.payload.totalCount") { value(1) } }
                }
            }

            `when`("표시 이름을 공백으로 주면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("김치찌개")

                    putUpdate(food.id, updateBody(koreanName = "김치찌개") + mapOf("displayName" to "  "))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMON-002") }
                        }
                }
            }

            `when`("version 을 누락하고 수정하면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("무버전찌개")

                    putUpdate(food.id, updateBody(koreanName = "무버전찌개") - "version").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("현재와 같은 version 을 실어 수정하면") {
                then("정상 반영되고 version 이 올라간다") {
                    val food = saveFood("버전찌개")

                    putUpdate(food.id, updateBody(koreanName = "버전찌개") + mapOf("version" to food.version))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.version") { value(food.version + 1) }
                        }
                }
            }

            `when`("다른 관리자가 먼저 수정해 version 이 달라졌으면") {
                then("409(FOOD-006) 로 거절하고 아무것도 저장하지 않는다") {
                    val food = saveFood("경합찌개")
                    putUpdate(food.id, updateBody(koreanName = "먼저수정")).andExpect { status { isOk() } }

                    putUpdate(food.id, updateBody(koreanName = "나중수정") + mapOf("version" to food.version))
                        .andExpect {
                            status { isConflict() }
                            jsonPath("$.code") { value("FOOD-006") }
                        }

                    foodJpaRepository.findById(food.id).orElseThrow().displayName shouldBe "먼저수정"
                }
            }

            `when`("같은 version 두 요청이 동시에 제출되면") {
                then("한쪽만 반영되고 다른 쪽은 FOOD-006 으로 거절된다") {
                    val food = saveFood("동시수정찌개")
                    val executor = Executors.newFixedThreadPool(2)
                    val startGate = CountDownLatch(1)

                    fun command(name: String) = UpdateFoodCommand(
                        koreanName = name,
                        description = "설명",
                        spiciness = 1,
                        contentStatus = FoodContentStatus.READY,
                        imageRef = "",
                        nameTranslationsJson = "",
                        descriptionTranslationsJson = "",
                        ingredientsJson = "",
                    )

                    val results = listOf("승자찌개", "패자찌개").map { name ->
                        executor.submit<Any> {
                            startGate.await()
                            try {
                                adminFoodService.updateFood(food.id, command(name), expectedVersion = food.version)
                            } catch (e: BusinessException) {
                                e.errorCode
                            }
                        }
                    }
                    startGate.countDown()
                    val outcomes = results.map { it.get() }
                    executor.shutdown()

                    outcomes.count { it == AdminFoodUpdateResult.UPDATED } shouldBe 1
                    outcomes.count { it == ErrorCode.FOOD_VERSION_CONFLICT } shouldBe 1
                }
            }

            `when`("성분을 생략하고 수정하면") {
                then("미조사(null)로 저장되고 응답도 null 이다") {
                    val food = saveFood("생략찌개")

                    val body = putUpdate(food.id, updateBody(koreanName = "생략찌개"))
                        .andExpect { status { isOk() } }
                        .andReturn().response.contentAsString

                    mapper.readTree(body).path("payload").path("ingredients").isNull shouldBe true
                    foodJpaRepository.findById(food.id).orElseThrow().ingredients shouldBe null
                }
            }

            `when`("성분을 빈 배열로 보내 수정하면") {
                then("조사 완료·해당 없음(빈 배열)으로 저장된다") {
                    val food = saveFood("빈성분찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "빈성분찌개") + mapOf("ingredients" to emptyList<Any>()),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.ingredients.length()") { value(0) }
                    }

                    foodJpaRepository.findById(food.id).orElseThrow().ingredients shouldBe emptyList()
                }
            }

            `when`("spiciness 가 스키마 범위(-1..10)를 벗어나면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("과맵찌개")

                    putUpdate(food.id, updateBody(koreanName = "과맵찌개") + mapOf("spiciness" to 11)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("description 이 DB 컬럼 한도(255자)를 넘으면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("긴설명찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "긴설명찌개") + mapOf("description" to "가".repeat(256)),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("성분 카탈로그에 없는 코드로 수정하면") {
                then("400(COMMON-002) 로 거절하고 아무것도 저장하지 않는다") {
                    val food = saveFood("된장찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "된장찌개") +
                            mapOf("ingredients" to listOf(mapOf("code" to "UNKNOWN_CODE", "inclusion_percent" to 50))),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }

                    foodJpaRepository.findById(food.id).orElseThrow().ingredients.orEmpty() shouldBe emptyList()
                }
            }
        }
    }
}
