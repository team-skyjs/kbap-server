package com.kbap.common.domain.food.model

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class FoodReviewTransitionTest : BehaviorSpec({
    val targetLangs = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }

    fun allTargets(value: String) = targetLangs.associateWith { "$value-$it" }

    fun pendingReview(reviewAttempts: Int = 0) = Food(
        koreanName = "된장찌개",
        imageRef = "images/food/1.webp",
        description = "구수한 된장찌개",
        spiciness = 3,
        nameTranslations = allTargets("된장찌개"),
        descriptionTranslations = allTargets("hearty stew"),
        avoidanceSubstances = listOf(FoodAvoidanceItem("SOYBEAN", 100)),
        contentStatus = FoodContentStatus.PENDING_REVIEW,
        reviewAttempts = reviewAttempts,
    )

    given("Food.passReview — AI 검수 통과") {
        `when`("PENDING_REVIEW 음식이 통과하면") {
            then("REVIEWED 로 전이하고 콘텐츠·시도 횟수는 그대로다") {
                val food = pendingReview()

                food.passReview()

                food.contentStatus shouldBe FoodContentStatus.REVIEWED
                food.reviewAttempts shouldBe 0
                food.description shouldBe "구수한 된장찌개"
                food.imageRef shouldBe "images/food/1.webp"
            }
        }

        `when`("이미 REVIEWED 인 음식에 통과가 다시 도착하면") {
            then("상태 변화 없이 멱등하게 성공한다") {
                val food = pendingReview().apply { passReview() }

                food.passReview()

                food.contentStatus shouldBe FoodContentStatus.REVIEWED
            }
        }

        `when`("검수 대상이 아닌 상태에서 통과가 도착하면") {
            then("예외를 던진다") {
                val food = pendingReview().apply { contentStatus = FoodContentStatus.INCOMPLETE }

                shouldThrow<IllegalArgumentException> { food.passReview() }
            }
        }
    }

    given("Food.rejectReview — 재시도 여력이 남은 탈락") {
        `when`("설명·설명 번역이 문제로 지목되면") {
            then("그 두 필드만 미채움으로 되돌리고 시도 횟수를 올린 뒤 INCOMPLETE 로 롤백한다") {
                val food = pendingReview()

                food.rejectReview(
                    setOf(FoodReviewField.DESCRIPTION, FoodReviewField.DESCRIPTION_TRANSLATIONS),
                    "설명이 음식과 무관함",
                )

                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                food.reviewAttempts shouldBe 1
                food.needsDescription() shouldBe true
                food.descriptionTranslations shouldBe emptyMap()
                food.nameTranslations shouldNotBe emptyMap<String, String>()
                food.avoidanceSubstances shouldBe listOf(FoodAvoidanceItem("SOYBEAN", 100))
                food.imageRef shouldBe "images/food/1.webp"
                food.reviewRejectionReason shouldBe null
            }
        }

        `when`("이미 1회 탈락한 음식이 다시 탈락하면") {
            then("시도 횟수가 2가 되고 여전히 롤백된다") {
                val food = pendingReview(reviewAttempts = 1)

                food.rejectReview(setOf(FoodReviewField.SPICINESS), null)

                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                food.reviewAttempts shouldBe 2
                food.spiciness shouldBe Food.SPICINESS_UNASSESSED
            }
        }

        `when`("이미지만 문제로 지목되면") {
            then("이미지를 비우고 텍스트가 온전하므로 PENDING_IMAGE 로 전이한다") {
                val food = pendingReview()

                food.rejectReview(setOf(FoodReviewField.IMAGE), null)

                food.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                food.imageRef shouldBe null
                food.reviewAttempts shouldBe 1
            }
        }

        `when`("기피 물질이 문제로 지목되면") {
            then("기피 물질을 미조사로 되돌린다") {
                val food = pendingReview()

                food.rejectReview(setOf(FoodReviewField.AVOIDANCE_SUBSTANCES), null)

                food.avoidanceSubstances shouldBe null
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("문제 필드가 하나도 지목되지 않으면") {
            then("예외를 던진다 — 무엇을 비울지 서버가 추측하지 않는다") {
                val food = pendingReview()

                shouldThrow<IllegalArgumentException> { food.rejectReview(emptySet(), "그냥 별로") }
            }
        }
    }

    given("Food.rejectReview — 재시도 소진 후 탈락") {
        `when`("시도 횟수가 이미 2인 음식이 탈락하면") {
            then("콘텐츠를 그대로 둔 채 REVIEW_REJECTED 로 전이하고 사유를 저장한다") {
                val food = pendingReview(reviewAttempts = Food.MAX_REVIEW_ATTEMPTS)

                food.rejectReview(setOf(FoodReviewField.DESCRIPTION), "설명이 여전히 부정확함")

                food.contentStatus shouldBe FoodContentStatus.REVIEW_REJECTED
                food.reviewAttempts shouldBe Food.MAX_REVIEW_ATTEMPTS
                food.description shouldBe "구수한 된장찌개"
                food.reviewRejectionReason shouldBe "설명이 여전히 부정확함"
            }
        }

        `when`("탈락 사유가 10줄을 넘으면") {
            then("앞의 10줄까지만 저장한다") {
                val food = pendingReview(reviewAttempts = Food.MAX_REVIEW_ATTEMPTS)

                food.rejectReview(setOf(FoodReviewField.DESCRIPTION), (1..15).joinToString("\n") { "사유 $it" })

                food.reviewRejectionReason shouldBe (1..Food.MAX_REJECTION_REASON_LINES).joinToString("\n") { "사유 $it" }
            }
        }
    }

    given("Food.transitionByContentState — 검수 단계 보호") {
        listOf(FoodContentStatus.REVIEWED, FoodContentStatus.REVIEW_REJECTED).forEach { status ->
            `when`("$status 음식을 콘텐츠 채움 배치가 훑으면") {
                then("상태를 임의로 되돌리지 않는다") {
                    val food = pendingReview().apply {
                        contentStatus = status
                        description = Food.PLACEHOLDER_DESCRIPTION
                    }

                    food.transitionByContentState() shouldBe status
                    food.contentStatus shouldBe status
                }
            }
        }

        `when`("탈락으로 INCOMPLETE 롤백된 음식의 콘텐츠가 다시 채워지면") {
            then("PENDING_REVIEW 로 재진입한다") {
                val food = pendingReview()
                food.rejectReview(setOf(FoodReviewField.DESCRIPTION, FoodReviewField.DESCRIPTION_TRANSLATIONS), null)

                food.updateDescription("담백한 된장찌개", allTargets("mild stew"))

                food.transitionByContentState() shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }
    }
})
