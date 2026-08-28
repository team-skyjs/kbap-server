package com.kbap.common.domain.food.model

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class FoodTransitionTest : BehaviorSpec({
    val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
    fun allTargets(v: String) = targets.associateWith { "$v-$it" }

    fun food(
        status: FoodContentStatus,
        imageRef: String? = "images/food/1.webp",
        ingredients: List<FoodIngredient>? = listOf(FoodIngredient("SOY", 100)),
        description: String = "구수한 된장찌개",
    ) = Food(
        koreanName = "된장찌개",
        imageRef = imageRef,
        description = description,
        spiciness = 3,
        nameTranslations = allTargets("n"),
        descriptionTranslations = allTargets("d"),
        ingredients = ingredients,
        contentStatus = status,
    )

    given("허용 전이 집합") {
        `when`("상태별로 조회하면") {
            then("PENDING_REVIEW 는 APPROVE·REJECT, FAILED 는 RESUBMIT, READY 는 UNPUBLISH, PENDING_IMAGE 는 없음") {
                food(FoodContentStatus.PENDING_REVIEW).allowedTransitions() shouldBe setOf(FoodTransition.APPROVE, FoodTransition.REJECT)
                food(FoodContentStatus.FAILED).allowedTransitions() shouldBe setOf(FoodTransition.RESUBMIT)
                food(FoodContentStatus.READY).allowedTransitions() shouldBe setOf(FoodTransition.UNPUBLISH)
                food(FoodContentStatus.PENDING_IMAGE).allowedTransitions() shouldBe emptySet()
            }
        }
    }

    given("APPROVE") {
        `when`("재료·이미지가 갖춰진 승인 대기 음식이면") {
            then("READY 가 된다") {
                val f = food(FoodContentStatus.PENDING_REVIEW)
                f.transition(FoodTransition.APPROVE)
                f.contentStatus shouldBe FoodContentStatus.READY
            }
        }

        `when`("이미지가 없으면") {
            then("전제 위반으로 거부하고 상태는 그대로다") {
                val f = food(FoodContentStatus.PENDING_REVIEW, imageRef = null)
                val e = shouldThrow<FoodTransitionException> { f.transition(FoodTransition.APPROVE) }
                e.reason shouldBe "NO_IMAGE"
                f.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }

        `when`("재료가 조사되지 않았으면(null)") {
            then("NO_INGREDIENTS 로 거부한다") {
                val f = food(FoodContentStatus.PENDING_REVIEW, ingredients = null)
                shouldThrow<FoodTransitionException> { f.transition(FoodTransition.APPROVE) }.reason shouldBe "NO_INGREDIENTS"
            }
        }

        `when`("approve() 를 직접 호출해도") {
            then("같은 전제를 검사한다") {
                shouldThrow<FoodTransitionException> { food(FoodContentStatus.PENDING_REVIEW, imageRef = null).approve() }
            }
        }
    }

    given("REJECT") {
        `when`("사유 없이 반려하면") {
            then("거부한다") {
                shouldThrow<FoodTransitionException> { food(FoodContentStatus.PENDING_REVIEW).transition(FoodTransition.REJECT, " ") }
                    .reason shouldBe "REASON_REQUIRED"
            }
        }

        `when`("사유와 함께 반려하면") {
            then("FAILED, 횟수 +1, 사유 기록") {
                val f = food(FoodContentStatus.PENDING_REVIEW)
                f.transition(FoodTransition.REJECT, "사진이 음식이 아님")
                f.contentStatus shouldBe FoodContentStatus.FAILED
                f.contentReviewAttempts shouldBe 1
                f.contentReviewRejectionReason shouldBe "사진이 음식이 아님"
            }
        }
    }

    given("RESUBMIT") {
        `when`("실패 음식에 콘텐츠·이미지가 있으면") {
            then("PENDING_REVIEW 로 가고 실패 유형·사유가 비워진다") {
                val f = food(FoodContentStatus.FAILED).apply {
                    contentFailureKind = FoodContentFailureKind.JUDGE_REJECTED
                    contentReviewRejectionReason = "이전 사유"
                }
                f.transition(FoodTransition.RESUBMIT)
                f.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                f.contentFailureKind.shouldBeNull()
                f.contentReviewRejectionReason.shouldBeNull()
            }
        }

        `when`("이미지가 없으면") {
            then("PENDING_IMAGE 로 간다") {
                val f = food(FoodContentStatus.FAILED, imageRef = null)
                f.transition(FoodTransition.RESUBMIT)
                f.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
            }
        }

        `when`("설명이 자리표시자이거나 재료가 없으면") {
            then("CONTENT_INCOMPLETE 로 거부한다") {
                shouldThrow<FoodTransitionException> {
                    food(FoodContentStatus.FAILED, description = Food.PLACEHOLDER_DESCRIPTION).transition(FoodTransition.RESUBMIT)
                }.reason shouldBe "CONTENT_INCOMPLETE"
                shouldThrow<FoodTransitionException> {
                    food(FoodContentStatus.FAILED, ingredients = null).transition(FoodTransition.RESUBMIT)
                }.reason shouldBe "CONTENT_INCOMPLETE"
            }
        }
    }

    given("UNPUBLISH") {
        `when`("READY 음식을 내리면") {
            then("PENDING_REVIEW 가 된다") {
                val f = food(FoodContentStatus.READY)
                f.transition(FoodTransition.UNPUBLISH)
                f.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }
    }

    given("비허용 전이") {
        `when`("READY 에 APPROVE 를 요청하면") {
            then("NOT_ALLOWED 와 허용 목록을 담아 거부한다") {
                val e = shouldThrow<FoodTransitionException> { food(FoodContentStatus.READY).transition(FoodTransition.APPROVE) }
                e.reason shouldBe "NOT_ALLOWED"
                e.allowed shouldContainExactly setOf(FoodTransition.UNPUBLISH)
                e.message shouldContain "READY"
            }
        }
    }

    given("replaceImage") {
        `when`("READY 음식의 이미지를 교체하면") {
            then("상태는 유지되고 imageRef 만 바뀐다") {
                val f = food(FoodContentStatus.READY)
                f.replaceImage("images/food/new.webp")
                f.contentStatus shouldBe FoodContentStatus.READY
                f.imageRef shouldBe "images/food/new.webp"
            }
        }

        `when`("PENDING_IMAGE 음식의 이미지를 교체하면") {
            then("PENDING_REVIEW 로 넘어간다") {
                val f = food(FoodContentStatus.PENDING_IMAGE, imageRef = null)
                f.replaceImage("images/food/new.webp")
                f.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }

        `when`("빈 참조를 주면") {
            then("거부한다") {
                shouldThrow<IllegalArgumentException> { food(FoodContentStatus.READY).replaceImage(" ") }
            }
        }
    }
})
