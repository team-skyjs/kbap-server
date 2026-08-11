package com.kbap.common.domain.food.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodContentFailureTest : BehaviorSpec({
    fun food(status: FoodContentStatus) =
        Food(
            koreanName = "들깨칼국수",
            displayName = "들깨 칼국수",
            imageRef = "foods/1.webp",
            description = "들깨를 곱게 갈아 넣어 고소한 칼국수",
            spiciness = 2,
            contentStatus = status,
            ingredients = emptyList(),
        )

    given("수집 실패 결과 기록") {
        `when`("이미 서비스 중(READY)인 음식이면") {
            val target = food(FoodContentStatus.READY)

            target.recordContentFailure(FoodContentFailureKind.JUDGE_REJECTED, "번역 점수 78점으로 임계값 미달")

            then("상태와 콘텐츠는 보존되고 실패 유형·사유만 남는다") {
                target.contentStatus shouldBe FoodContentStatus.READY
                target.description shouldBe "들깨를 곱게 갈아 넣어 고소한 칼국수"
                target.ingredients shouldBe emptyList()
                target.contentFailureKind shouldBe FoodContentFailureKind.JUDGE_REJECTED
                target.contentReviewRejectionReason shouldBe "번역 점수 78점으로 임계값 미달"
                target.contentReviewAttempts shouldBe 1
            }
        }

        `when`("서비스 중이 아닌 음식이면") {
            val target = food(FoodContentStatus.PENDING_IMAGE)

            target.recordContentFailure(FoodContentFailureKind.NOT_FOOD, "콘텐츠 생성 부적합: 비음식")

            then("관리자 확인 상태로 내려간다") {
                target.contentStatus shouldBe FoodContentStatus.FAILED
                target.contentFailureKind shouldBe FoodContentFailureKind.NOT_FOOD
                target.contentReviewAttempts shouldBe 1
            }
        }

        `when`("실패가 두 번 기록되면") {
            val target = food(FoodContentStatus.FAILED)

            target.recordContentFailure(FoodContentFailureKind.NOT_FOOD, "1차")
            target.recordContentFailure(FoodContentFailureKind.INGREDIENT_GUARD, "2차")

            then("횟수가 누적되고 마지막 유형·사유가 남는다") {
                target.contentReviewAttempts shouldBe 2
                target.contentFailureKind shouldBe FoodContentFailureKind.INGREDIENT_GUARD
                target.contentReviewRejectionReason shouldBe "2차"
            }
        }

        `when`("사유가 10줄을 넘으면") {
            val target = food(FoodContentStatus.FAILED)

            target.recordContentFailure(FoodContentFailureKind.JUDGE_REJECTED, (1..20).joinToString("\n"))

            then("앞 10줄만 저장된다") {
                target.contentReviewRejectionReason shouldBe (1..10).joinToString("\n")
            }
        }

        `when`("사유가 1000자를 넘으면") {
            val target = food(FoodContentStatus.FAILED)

            target.recordContentFailure(FoodContentFailureKind.JUDGE_REJECTED, "가".repeat(1500))

            then("1000자로 잘린다") {
                target.contentReviewRejectionReason shouldBe "가".repeat(Food.MAX_REJECTION_REASON_LENGTH)
            }
        }
    }
})
