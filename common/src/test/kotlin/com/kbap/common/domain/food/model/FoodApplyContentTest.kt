package com.kbap.common.domain.food.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class FoodApplyContentTest : BehaviorSpec({
    val translations = mapOf("en" to "Perilla Kalguksu", "ja" to "えごまカルグクス")
    val ingredients = listOf(FoodIngredient(code = "SESAME", inclusionPercent = 100))

    fun food(status: FoodContentStatus, imageRef: String?) =
        Food(
            koreanName = "들깨칼국수",
            displayName = "들깨 칼국수",
            imageRef = imageRef,
            description = Food.PLACEHOLDER_DESCRIPTION,
            spiciness = Food.SPICINESS_UNASSESSED,
            contentStatus = status,
            ingredients = null,
        )

    fun Food.applySample() =
        applyContent(
            description = "들깨를 곱게 갈아 넣어 고소한 칼국수",
            spiciness = 2,
            nameTranslations = translations,
            descriptionTranslations = translations,
            ingredients = ingredients,
        )

    given("콘텐츠 적재") {
        `when`("이미 서비스 중(READY)인 음식이면") {
            val target = food(FoodContentStatus.READY, "foods/1.webp")

            target.applySample()

            then("텍스트만 갱신되고 상태와 사진은 그대로다") {
                target.description shouldBe "들깨를 곱게 갈아 넣어 고소한 칼국수"
                target.spiciness shouldBe 2
                target.nameTranslations shouldBe translations
                target.descriptionTranslations shouldBe translations
                target.ingredients shouldBe ingredients
                target.contentStatus shouldBe FoodContentStatus.READY
                target.imageRef shouldBe "foods/1.webp"
            }
        }

        `when`("서비스 중이 아니고 사진이 이미 있으면") {
            val target = food(FoodContentStatus.FAILED, "foods/2.webp")

            target.applySample()

            then("승인 대기로 가고 사진은 재활용된다") {
                target.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                target.imageRef shouldBe "foods/2.webp"
            }
        }

        `when`("서비스 중이 아니고 사진이 없으면") {
            val target = food(FoodContentStatus.FAILED, null)

            target.applySample()

            then("이미지 생성 대기가 된다") {
                target.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
            }
        }

        `when`("사진이 빈 문자열이면") {
            val target = food(FoodContentStatus.FAILED, "")

            target.applySample()

            then("사진 없음으로 보아 이미지 생성 대기가 된다") {
                target.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
            }
        }

        `when`("직전 실패 기록이 남아 있으면") {
            val target = food(FoodContentStatus.FAILED, null)
            target.recordContentFailure(FoodContentFailureKind.JUDGE_REJECTED, "번역 점수 미달")

            target.applySample()

            then("실패 유형과 사유가 초기화된다") {
                target.contentFailureKind.shouldBeNull()
                target.contentReviewRejectionReason.shouldBeNull()
            }
        }
    }
})
