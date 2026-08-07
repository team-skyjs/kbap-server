package com.kbap.common.domain.food.model

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodApprovalTransitionTest : BehaviorSpec({
    val targetLangs = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }

    fun allTargets(value: String) = targetLangs.associateWith { "$value-$it" }

    fun food(status: FoodContentStatus, imageRef: String? = "images/food/1.webp") = Food(
        koreanName = "된장찌개",
        imageRef = imageRef,
        description = "구수한 된장찌개",
        spiciness = 3,
        nameTranslations = allTargets("된장찌개"),
        descriptionTranslations = allTargets("hearty stew"),
        ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
        contentStatus = status,
    )

    given("Food.approve — 관리자 승인") {
        `when`("승인 대기(PENDING_REVIEW) 음식을 승인하면") {
            then("조회 가능(READY)으로 전이한다") {
                val target = food(FoodContentStatus.PENDING_REVIEW)

                target.approve()

                target.contentStatus shouldBe FoodContentStatus.READY
                target.isReady() shouldBe true
            }
        }

        `when`("이미 승인된 음식에 승인이 다시 도착하면") {
            then("상태 변화 없이 멱등하게 성공한다") {
                val target = food(FoodContentStatus.READY)

                target.approve()

                target.contentStatus shouldBe FoodContentStatus.READY
            }
        }

        listOf(FoodContentStatus.FAILED, FoodContentStatus.PENDING_IMAGE).forEach { status ->
            `when`("$status 음식을 승인하려 하면") {
                then("예외를 던진다") {
                    shouldThrow<IllegalArgumentException> { food(status).approve() }
                }
            }
        }
    }

    given("Food.reject — 관리자 반려") {
        `when`("승인 대기 음식을 반려하면") {
            then("관리자 확인 필요(FAILED)로 전이하고 사유를 기록한다") {
                val target = food(FoodContentStatus.PENDING_REVIEW)

                target.reject("설명이 음식과 무관함")

                target.contentStatus shouldBe FoodContentStatus.FAILED
                target.contentReviewRejectionReason shouldBe "설명이 음식과 무관함"
            }
        }

        `when`("반려해도") {
            then("콘텐츠는 그대로 보존한다 — 랭체인이 재수집하므로 서버가 비우지 않는다") {
                val target = food(FoodContentStatus.PENDING_REVIEW)

                target.reject(null)

                target.description shouldBe "구수한 된장찌개"
                target.imageRef shouldBe "images/food/1.webp"
                target.ingredients shouldBe listOf(FoodIngredient("SOYBEAN", 100))
                target.nameTranslations shouldBe allTargets("된장찌개")
            }
        }

        `when`("반려가 반복되면") {
            then("반려 횟수가 누적된다 — 관리자 판단 참고용") {
                val target = food(FoodContentStatus.PENDING_REVIEW)

                target.reject(null)
                target.resubmit()
                target.attachImage("images/food/2.webp")
                target.reject(null)

                target.contentReviewAttempts shouldBe 2
            }
        }

        `when`("반려 사유가 10줄을 넘으면") {
            then("앞의 10줄까지만 저장한다") {
                val target = food(FoodContentStatus.PENDING_REVIEW)

                target.reject((1..15).joinToString("\n") { "사유 $it" })

                target.contentReviewRejectionReason shouldBe
                    (1..Food.MAX_REJECTION_REASON_LINES).joinToString("\n") { "사유 $it" }
            }
        }

        `when`("한 줄짜리 사유가 컬럼 길이를 넘으면") {
            then("컬럼 길이까지만 잘라 저장한다 — 저장 실패로 반려 결과를 잃지 않는다") {
                val target = food(FoodContentStatus.PENDING_REVIEW)

                target.reject("가".repeat(1_500))

                target.contentReviewRejectionReason shouldBe "가".repeat(Food.MAX_REJECTION_REASON_LENGTH)
            }
        }

        `when`("승인 대기가 아닌 음식을 반려하려 하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { food(FoodContentStatus.PENDING_IMAGE).reject(null) }
            }
        }
    }

    given("Food.resubmit — 관리자 수정 완료 후 재투입") {
        `when`("관리자 확인 필요(FAILED) 음식을 재투입하면") {
            then("이미지 대기(PENDING_IMAGE)로 전이한다") {
                val target = food(FoodContentStatus.FAILED, imageRef = null)

                target.resubmit()

                target.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
            }
        }

        `when`("FAILED 가 아닌 음식을 재투입하려 하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { food(FoodContentStatus.READY).resubmit() }
            }
        }
    }

    given("Food.attachImage — 이미지 회수") {
        `when`("이미지 대기 음식에 이미지를 붙이면") {
            then("imageRef 저장과 함께 승인 대기(PENDING_REVIEW)로 전이한다") {
                val target = food(FoodContentStatus.PENDING_IMAGE, imageRef = null)

                target.attachImage("images/food/7.png")

                target.imageRef shouldBe "images/food/7.png"
                target.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }

        `when`("이미지 대기가 아닌 음식에 이미지가 도착하면") {
            then("예외를 던진다 — 이미지 생성 후보는 PENDING_IMAGE 뿐이다") {
                shouldThrow<IllegalArgumentException> {
                    food(FoodContentStatus.FAILED, imageRef = null).attachImage("images/food/8.png")
                }
            }
        }

        `when`("imageRef 가 blank 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    food(FoodContentStatus.PENDING_IMAGE, imageRef = null).attachImage("  ")
                }
            }
        }
    }

    given("Food.failed — 스캔 센티널 생성") {
        `when`("메뉴판 스캔이 미수집 음식을 적재하면") {
            then("관리자 확인 필요(FAILED)로 시작하고 미조사 센티널을 유지한다") {
                val target = Food.failed("된장찌개", "된장찌개 (2인분)")

                target.contentStatus shouldBe FoodContentStatus.FAILED
                target.koreanName shouldBe "된장찌개"
                target.displayName shouldBe "된장찌개 (2인분)"
                target.ingredients shouldBe null
                target.spiciness shouldBe Food.SPICINESS_UNASSESSED
                target.description shouldBe Food.PLACEHOLDER_DESCRIPTION
            }
        }
    }
})
