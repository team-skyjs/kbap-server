package com.kbap.domain.food.model

import com.kbap.core.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodPendingReviewTransitionTest : BehaviorSpec({
    val targetLangs = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }

    fun allTargets(value: String) = targetLangs.associateWith { "$value-$it" }

    fun incomplete(
        imageRef: String? = "s3://img/doenjang.jpg",
        description: String = "구수한 된장찌개",
        nameTranslations: Map<String, String> = allTargets("된장찌개"),
        descriptionTranslations: Map<String, String> = allTargets("hearty stew"),
        avoidanceSubstances: List<FoodAvoidanceItem>? = listOf(FoodAvoidanceItem("SOYBEAN", 100)),
        spiciness: Int = 3,
    ) = Food(
        koreanName = "된장찌개",
        imageRef = imageRef,
        description = description,
        spiciness = spiciness,
        nameTranslations = nameTranslations,
        descriptionTranslations = descriptionTranslations,
        avoidanceSubstances = avoidanceSubstances,
        contentStatus = FoodContentStatus.INCOMPLETE,
    )

    given("Food.transitionToPendingReviewIfComplete — 4작업 완비") {
        `when`("사진·설명·이름 번역·설명 번역이 모두 채워지고 기피성분 매핑이 있으면") {
            then("검수 대기(PENDING_REVIEW)로 전이하고 true 를 반환한다") {
                val food = incomplete()

                food.transitionToPendingReviewIfComplete() shouldBe true
                food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }

        `when`("나머지가 완비되고 맵기가 조사값 0(안 매움)이면") {
            then("맵기 값(0~10)은 게이트가 아니므로 검수 대기로 전이한다") {
                val food = incomplete(spiciness = 0)

                food.transitionToPendingReviewIfComplete() shouldBe true
                food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }

        `when`("나머지가 완비되어도 맵기가 미조사 센티널(-1)이면") {
            then("미조사 상태로는 전이하지 않고 INCOMPLETE 를 유지한다") {
                val food = incomplete(spiciness = Food.SPICINESS_UNASSESSED)

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToPendingReviewIfComplete — 사진 누락") {
        `when`("imageRef 가 null 이면") {
            then("전이하지 않고 INCOMPLETE 를 유지하며 false 를 반환한다") {
                val food = incomplete(imageRef = null)

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("imageRef 가 blank 이면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(imageRef = "  ")

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToPendingReviewIfComplete — 설명 미완성") {
        `when`("description 이 blank 이면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(description = "")

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("description 이 placeholder(설명 준비 중) 그대로이면") {
            then("아직 생성 전이므로 전이하지 않고 false 를 반환한다") {
                val food = incomplete(description = Food.PLACEHOLDER_DESCRIPTION)

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToPendingReviewIfComplete — 번역 미완비") {
        `when`("이름 번역이 9개 대상 언어 중 하나(ja)를 빠뜨리면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(
                    nameTranslations = allTargets("된장찌개") - LanguageCode.JA.code,
                )

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("설명 번역이 비어 있으면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(descriptionTranslations = emptyMap())

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("이름 번역에 9개 키가 다 있어도 값 하나(ja)가 blank 이면") {
            then("미완으로 보고 전이하지 않는다") {
                val food = incomplete(
                    nameTranslations = allTargets("된장찌개") + (LanguageCode.JA.code to " "),
                )

                food.needsNameTranslations() shouldBe true
                food.transitionToPendingReviewIfComplete() shouldBe false
            }
        }

        `when`("설명 번역에 9개 키가 다 있어도 값 하나(en)가 blank 이면") {
            then("미완으로 보고 전이하지 않는다") {
                val food = incomplete(
                    descriptionTranslations = allTargets("hearty stew") + (LanguageCode.EN.code to ""),
                )

                food.needsDescriptionTranslations() shouldBe true
                food.transitionToPendingReviewIfComplete() shouldBe false
            }
        }
    }

    given("Food.transitionToPendingReviewIfComplete — 기피성분 조사 상태") {
        `when`("콘텐츠 3필드가 완비되어도 기피성분이 미조사(null)이면") {
            then("안전 직결이라 전이하지 않고 false 를 반환한다") {
                val food = incomplete(avoidanceSubstances = null)

                food.transitionToPendingReviewIfComplete() shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("콘텐츠 3필드가 완비되고 기피성분이 빈 목록(무성분 조사완료)이면") {
            then("조사완료이므로 검수 대기로 전이하고 true 를 반환한다") {
                val food = incomplete(avoidanceSubstances = emptyList())

                food.transitionToPendingReviewIfComplete() shouldBe true
                food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }
    }

    given("Food.transitionToPendingReviewIfComplete — 완비 상태 멱등") {
        `when`("이미 검수 대기(PENDING_REVIEW)인 음식에 다시 전이를 시도하면") {
            then("상태 불변으로 PENDING_REVIEW 를 유지하고 true 를 반환한다") {
                val food = incomplete()
                food.transitionToPendingReviewIfComplete() shouldBe true

                food.transitionToPendingReviewIfComplete() shouldBe true
                food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
            }
        }

        `when`("이미 승인된(READY) 음식에 전이를 시도하면") {
            then("재평가하지 않고 READY 를 유지하며 true 를 반환한다") {
                val food = incomplete()
                food.contentStatus = FoodContentStatus.READY

                food.transitionToPendingReviewIfComplete() shouldBe true
                food.contentStatus shouldBe FoodContentStatus.READY
            }
        }
    }

    given("Food.needsX — 작업별 완료 여부(배치 skip 근거)") {
        `when`("모든 콘텐츠가 채워진 음식이면") {
            then("네 작업 모두 needs=false 다") {
                val food = incomplete()

                food.needsImage() shouldBe false
                food.needsDescription() shouldBe false
                food.needsNameTranslations() shouldBe false
                food.needsDescriptionTranslations() shouldBe false
                food.needsAvoidanceMapping() shouldBe false
            }
        }

        `when`("이미지가 비어 있으면") {
            then("needsImage 만 true 다") {
                val food = incomplete(imageRef = null)

                food.needsImage() shouldBe true
                food.needsDescription() shouldBe false
            }
        }

        `when`("설명이 placeholder 이면") {
            then("needsDescription 이 true 다") {
                incomplete(description = Food.PLACEHOLDER_DESCRIPTION).needsDescription() shouldBe true
            }
        }

        `when`("이름 번역이 한 언어라도 빠지면") {
            then("needsNameTranslations 가 true 다") {
                incomplete(
                    nameTranslations = allTargets("된장찌개") - LanguageCode.JA.code,
                ).needsNameTranslations() shouldBe true
            }
        }

        `when`("기피성분이 미조사(null)이면") {
            then("needsAvoidanceMapping 이 true 다") {
                incomplete(avoidanceSubstances = null).needsAvoidanceMapping() shouldBe true
            }
        }

        `when`("기피성분이 빈 목록(무성분 조사완료)이면") {
            then("조사완료라 needsAvoidanceMapping 이 false 다") {
                incomplete(avoidanceSubstances = emptyList()).needsAvoidanceMapping() shouldBe false
            }
        }
    }
})
