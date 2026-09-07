package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@IntegrationTest
class AdminFoodCatalogLifecycleControllerTest : AdminFoodCatalogTestSupport() {
    init {
        given("어드민 음식 재수집 API") {
            `when`("단건 재수집을 요청하면") {
                then("콘텐츠 수집 대기가 생성된다") {
                    val food = saveFood("재수집찌개")

                    recollectOne(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.requested") { value(1) }
                        jsonPath("$.payload.created") { value(1) }
                        jsonPath("$.payload.skipped") { value(0) }
                    }
                }
            }

            `when`("이미 수집 대기 중인 음식에 단건 재수집을 요청하면") {
                then("생성 없이 건너뛴다") {
                    val food = saveFood("대기중찌개")
                    recollectOne(food.id).andExpect { status { isOk() } }

                    recollectOne(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.created") { value(0) }
                        jsonPath("$.payload.skipped") { value(1) }
                    }
                }
            }

            `when`("같은 음식에 단건 재수집이 동시에 들어오면") {
                then("수집 대기는 정확히 한 건만 생성된다") {
                    val food = saveFood("동시성찌개")
                    val executor = Executors.newFixedThreadPool(2)
                    val startGate = CountDownLatch(1)

                    val results = (1..2).map {
                        executor.submit<AdminFoodRecollectResult> {
                            startGate.await()
                            adminFoodService.requestRecollectForFood(food.id)
                        }
                    }
                    startGate.countDown()
                    val outcomes = results.map { it.get() }
                    executor.shutdown()

                    outcomes.sumOf { it.created } shouldBe 1
                    outcomes.sumOf { it.skipped } shouldBe 1
                    foodContentOutboxJpaRepository
                        .findByFoodIdInAndOutboxStatus(listOf(food.id), FoodContentOutboxStatus.PENDING)
                        .size shouldBe 1
                }
            }

            `when`("없는 id 에 단건 재수집을 요청하면") {
                then("400(FOOD-001) 로 거절한다") {
                    recollectOne(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("검색어 필터로 일괄 재수집을 요청하면") {
                then("일치 건만 대기를 만들고 카운트를 내려준다") {
                    saveFood("김치찌개")
                    saveFood("김치볶음밥")
                    saveFood("된장찌개")

                    recollectBulk("?q=김치").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.requested") { value(2) }
                        jsonPath("$.payload.created") { value(2) }
                        jsonPath("$.payload.skipped") { value(0) }
                        jsonPath("$.payload.exceeded") { value(false) }
                    }
                }
            }
        }

        given("어드민 삭제 음식 목록 조회 API") {
            `when`("삭제된 음식과 활성 음식이 섞여 있으면") {
                then("삭제된 음식만 삭제 시각과 함께 내려준다") {
                    saveFood("활성찌개")
                    val first = saveFood("삭제찌개1")
                    val second = saveFood("삭제찌개2")
                    deleteFood(first.id).andExpect { status { isOk() } }
                    deleteFood(second.id).andExpect { status { isOk() } }

                    getDeletedList().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.totalCount") { value(2) }
                        jsonPath("$.payload.items[0].updatedAt") { exists() }
                    }
                }
            }
        }

        given("어드민 삭제 음식 상세 조회 API") {
            `when`("삭제된 음식을 조회하면") {
                then("기존 상세와 같은 형태로 내려준다") {
                    val food = saveFood("복원후보찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }

                    getDeletedDetail(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.id") { value(food.id) }
                        jsonPath("$.payload.koreanName") { value("복원후보찌개") }
                        jsonPath("$.payload.matchKey") { value("복원후보찌개") }
                        jsonPath("$.payload.deleted") { value(true) }
                        jsonPath("$.payload.contentStatus") { value("READY") }
                    }
                }
            }

            `when`("활성 음식 id 로 조회하면") {
                then("400(FOOD-001) 로 거절한다 — 삭제 전용 뷰") {
                    val food = saveFood("활성찌개")

                    getDeletedDetail(food.id).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("없는 id 로 조회하면") {
                then("400(FOOD-001) 로 거절한다") {
                    getDeletedDetail(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
        }

        given("어드민 음식 복원 API") {
            `when`("삭제된 READY 음식을 복원하면") {
                then("다시 조회되고 벡터 UPSERT 동기화가 큐잉된다") {
                    val food = saveFood("복원찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }

                    postRestore(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.restored") { value(true) }
                        jsonPath("$.payload.contentStatus") { value("READY") }
                    }

                    getDetail(food.id).andExpect { status { isOk() } }
                    hasPendingUpsertOutbox(food.id) shouldBe true
                }
            }

            `when`("대기 중인 벡터 삭제 큐가 남은 채로 READY 음식을 복원하면") {
                then("삭제 큐는 취소되고 UPSERT 만 남는다 — 어떤 처리 순서에도 벡터가 살아남는다") {
                    val food = saveFood("경합복원찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }
                    hasPendingOutbox(food.id, FoodVectorOutboxOperation.DELETE) shouldBe true

                    postRestore(food.id).andExpect { status { isOk() } }

                    hasPendingOutbox(food.id, FoodVectorOutboxOperation.DELETE) shouldBe false
                    hasPendingUpsertOutbox(food.id) shouldBe true
                }
            }

            `when`("복원하면 이름도 원명으로 돌아온다") {
                then("매치키(koreanName)가 삭제 전 값으로 복구된다") {
                    val food = saveFood("원명찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }

                    postRestore(food.id).andExpect { status { isOk() } }

                    foodJpaRepository.findById(food.id).orElseThrow().koreanName shouldBe "원명찌개"
                }
            }

            `when`("접두 233자가 같은 두 한도 이름을 연달아 삭제하면") {
                then("개명 키가 충돌하지 않고 둘 다 삭제된다") {
                    val first = foodJpaRepository.save(Food(koreanName = "가".repeat(254) + "나", description = "설명"))
                    val second = foodJpaRepository.save(Food(koreanName = "가".repeat(254) + "다", description = "설명"))

                    deleteFood(first.id).andExpect { status { isOk() } }
                    deleteFood(second.id).andExpect { status { isOk() } }

                    getDeletedList().andExpect { jsonPath("$.payload.totalCount") { value(2) } }
                }
            }

            `when`("한도(255자) 이름의 음식을 삭제 후 복원하면") {
                then("원명이 잘리지 않고 정확히 복구된다") {
                    val longName = "가".repeat(255)
                    val food = foodJpaRepository.save(Food(koreanName = longName, description = "설명"))
                    deleteFood(food.id).andExpect { status { isOk() } }

                    postRestore(food.id).andExpect { status { isOk() } }

                    foodJpaRepository.findById(food.id).orElseThrow().koreanName shouldBe longName
                }
            }

            `when`("복원 커밋 직전 유니크 충돌이 나면") {
                then("500 이 아니라 409(FOOD-009) 로 응답한다") {
                    val food = saveFood("커밋레이스찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }
                    val occupant = saveFood("커밋레이스찌개")
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute("UPDATE food SET status = 'DELETED' WHERE id = ${occupant.id}")
                        }
                    }

                    postRestore(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-009") }
                    }
                }
            }

            `when`("삭제 사이 같은 이름의 음식이 새로 생겼으면") {
                then("409(FOOD-009) 이름 충돌로 복원을 거절한다") {
                    val food = saveFood("동명찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }
                    saveFood("동명찌개")

                    postRestore(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-009") }
                    }
                }
            }

            `when`("삭제된 비 READY 음식을 복원하면") {
                then("복원은 되지만 벡터 동기화는 큐잉하지 않는다") {
                    val food = saveFood("실패찌개", FoodContentStatus.FAILED)
                    deleteFood(food.id).andExpect { status { isOk() } }

                    postRestore(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.restored") { value(true) }
                        jsonPath("$.payload.contentStatus") { value("FAILED") }
                    }

                    hasPendingUpsertOutbox(food.id) shouldBe false
                }
            }

            `when`("이미 활성인 음식을 복원하면") {
                then("변경 없이 restored=false 로 멱등하다") {
                    val food = saveFood("활성찌개")

                    postRestore(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.restored") { value(false) }
                    }
                }
            }

            `when`("없는 id 를 복원하면") {
                then("400(FOOD-001) 로 거절한다") {
                    postRestore(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
        }

        given("어드민 음식 소프트삭제 API") {
            `when`("대기 중인 벡터 UPSERT 큐가 남은 채로 삭제하면") {
                then("UPSERT 는 취소되고 DELETE 만 남는다 — 삭제된 음식의 벡터가 되살아나지 않는다") {
                    val food = saveFood("경합삭제찌개")
                    foodVectorOutboxJpaRepository.save(FoodVectorOutbox.upsert(food.id))

                    deleteFood(food.id).andExpect { status { isOk() } }

                    hasPendingUpsertOutbox(food.id) shouldBe false
                    hasPendingOutbox(food.id, FoodVectorOutboxOperation.DELETE) shouldBe true
                }
            }

            `when`("삭제된 음식과 같은 이름으로 다른 음식을 수정하면") {
                then("유니크 충돌 없이 성공한다 — 삭제가 이름을 반납한다") {
                    val deleted = saveFood("김치찌개")
                    deleteFood(deleted.id).andExpect { status { isOk() } }
                    val other = saveFood("된장찌개")

                    putUpdate(other.id, updateBody(koreanName = "김치찌개")).andExpect {
                        status { isOk() }
                    }
                }
            }

            `when`("대기 중인 콘텐츠 수집 큐가 남은 채로 삭제하면") {
                then("수집 대기도 취소된다 — 삭제 음식이 랭체인 파이프라인에 발행되지 않는다") {
                    val food = saveFood("수집대기삭제찌개")
                    recollectOne(food.id).andExpect { status { isOk() } }

                    deleteFood(food.id).andExpect { status { isOk() } }

                    foodContentOutboxJpaRepository
                        .findByFoodIdInAndOutboxStatus(listOf(food.id), FoodContentOutboxStatus.PENDING)
                        .size shouldBe 0
                }
            }

            `when`("음식을 삭제하면") {
                then("목록·상세에서 사라진다") {
                    val food = saveFood("삭제할찌개")

                    deleteFood(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    getList().andExpect { jsonPath("$.payload.totalCount") { value(0) } }
                }
            }

            `when`("없는 id 를 삭제하면") {
                then("400(FOOD-001) 로 거절한다") {
                    deleteFood(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
        }
    }
}
