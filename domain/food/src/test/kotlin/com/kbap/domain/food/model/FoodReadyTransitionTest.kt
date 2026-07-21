package com.kbap.domain.food.model

import com.kbap.core.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodReadyTransitionTest : BehaviorSpec({
    val targetLangs = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }

    fun allTargets(value: String) = targetLangs.associateWith { "$value-$it" }

    fun incomplete(
        imageRef: String? = "s3://img/doenjang.jpg",
        description: String = "구수한 된장찌개",
        nameTranslations: Map<String, String> = allTargets("된장찌개"),
        descriptionTranslations: Map<String, String> = allTargets("hearty stew"),
    ) = Food(
        koreanName = "된장찌개",
        imageRef = imageRef,
        description = description,
        nameTranslations = nameTranslations,
        descriptionTranslations = descriptionTranslations,
        contentStatus = FoodContentStatus.INCOMPLETE,
    )

    given("Food.transitionToReadyIfComplete — 4작업 완비") {
        `when`("사진·설명·이름 번역·설명 번역이 모두 채워지고 기피성분 매핑이 있으면") {
            then("READY 로 전이하고 true 를 반환한다") {
                val food = incomplete()

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe true
                food.contentStatus shouldBe FoodContentStatus.READY
            }
        }

        `when`("나머지가 완비되고 spiciness 가 기본 0 이어도") {
            then("맵기는 게이트가 아니므로 READY 로 전이한다") {
                val food = incomplete()
                food.spiciness shouldBe 0

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe true
                food.contentStatus shouldBe FoodContentStatus.READY
            }
        }
    }

    given("Food.transitionToReadyIfComplete — 사진 누락") {
        `when`("imageRef 가 null 이면") {
            then("전이하지 않고 INCOMPLETE 를 유지하며 false 를 반환한다") {
                val food = incomplete(imageRef = null)

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("imageRef 가 blank 이면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(imageRef = "  ")

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToReadyIfComplete — 설명 미완성") {
        `when`("description 이 blank 이면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(description = "")

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("description 이 placeholder(설명 준비 중) 그대로이면") {
            then("아직 생성 전이므로 전이하지 않고 false 를 반환한다") {
                val food = incomplete(description = Food.PLACEHOLDER_DESCRIPTION)

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToReadyIfComplete — 번역 미완비") {
        `when`("이름 번역이 9개 대상 언어 중 하나(ja)를 빠뜨리면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(
                    nameTranslations = allTargets("된장찌개") - LanguageCode.JA.code,
                )

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }

        `when`("설명 번역이 비어 있으면") {
            then("전이하지 않고 false 를 반환한다") {
                val food = incomplete(descriptionTranslations = emptyMap())

                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToReadyIfComplete — 기피성분 매핑 부재") {
        `when`("콘텐츠 3필드가 완비되어도 기피성분 매핑이 없으면") {
            then("안전 직결이라 전이하지 않고 false 를 반환한다") {
                val food = incomplete()

                food.transitionToReadyIfComplete(hasAvoidanceMapping = false) shouldBe false
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
            }
        }
    }

    given("Food.transitionToReadyIfComplete — 멱등") {
        `when`("이미 READY 인 음식에 다시 전이를 시도하면") {
            then("상태 불변으로 READY 를 유지하고 true 를 반환한다") {
                val food = incomplete()
                food.transitionToReadyIfComplete(hasAvoidanceMapping = true) shouldBe true

                food.transitionToReadyIfComplete(hasAvoidanceMapping = false) shouldBe true
                food.contentStatus shouldBe FoodContentStatus.READY
            }
        }
    }
})
