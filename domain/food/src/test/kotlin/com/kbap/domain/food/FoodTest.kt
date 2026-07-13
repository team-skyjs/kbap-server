package com.kbap.domain.food

import com.kbap.core.lang.LanguageCode
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.core.lang.LocalizedText
import com.kbap.core.risk.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodTest : BehaviorSpec({
    fun substance(code: String, probability: Int) =
        FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef(code), inclusionProbability = probability)

    val baseContent = FoodContent(
        name = LocalizedText(korean = "된장찌개"),
        description = LocalizedText(korean = "구수한 한국식 된장찌개"),
    )

    fun create(
        content: FoodContent = baseContent,
        spiciness: FoodSpiciness = FoodSpiciness(3),
        avoidanceSubstances: List<FoodAvoidanceSubstance> = listOf(substance("SOYBEAN", 100)),
    ) = Food.create(
        content = content,
        spiciness = spiciness,
        avoidanceSubstances = avoidanceSubstances,
    )

    given("Food.create — 구성·맵기 보존") {
        `when`("정상 값으로 생성하면") {
            then("content 와 맵기를 그대로 보존한다") {
                val food = create()

                food.content.name.korean shouldBe "된장찌개"
                food.content.description.korean shouldBe "구수한 한국식 된장찌개"
                food.spiciness.value shouldBe 3
            }
        }
    }

    given("Food.displayName / description — 요청 언어 표시값(ko 폴백)") {
        val localized = create(
            content = FoodContent(
                name = LocalizedText(korean = "된장찌개", translations = mapOf(LanguageCode.EN to "Doenjang Stew")),
                description = LocalizedText(korean = "구수한 된장찌개", translations = mapOf(LanguageCode.EN to "A hearty stew")),
            ),
        )

        `when`("번역이 있는 언어로 조회하면") {
            then("해당 언어 표시값을 반환한다") {
                localized.displayName(LanguageCode.EN) shouldBe "Doenjang Stew"
                localized.description(LanguageCode.EN) shouldBe "A hearty stew"
            }
        }

        `when`("번역이 없는 지원 언어로 조회하면") {
            then("한국어 원문으로 폴백한다") {
                localized.displayName(LanguageCode.JA) shouldBe "된장찌개"
                localized.description(LanguageCode.JA) shouldBe "구수한 된장찌개"
            }
        }

        `when`("한국어로 조회하면") {
            then("한국어 원문을 반환한다") {
                localized.displayName(LanguageCode.KO) shouldBe "된장찌개"
                localized.description(LanguageCode.KO) shouldBe "구수한 된장찌개"
            }
        }
    }

    given("Food.koreanName — 언어 무관 한국어 원문") {
        `when`("영어 번역이 있는 음식이어도") {
            then("content 의 한국어 원문 이름을 반환한다") {
                val food = create(
                    content = FoodContent(
                        name = LocalizedText(korean = "된장찌개", translations = mapOf(LanguageCode.EN to "Doenjang Stew")),
                        description = LocalizedText(korean = "구수한 된장찌개"),
                    ),
                )

                food.koreanName() shouldBe "된장찌개"
            }
        }
    }

    given("Food.avoidanceSubstancesByProbability") {
        `when`("포함 성분이 포함 확률 내림차순이 아닌 순서로 담겨 있으면") {
            then("포함 확률 내림차순으로 정렬된 성분을 반환한다") {
                val food = create(
                    avoidanceSubstances = listOf(
                        substance("TOFU", 90),
                        substance("SOYBEAN", 100),
                        substance("CLAM", 50),
                    ),
                )

                food.avoidanceSubstancesByProbability().map { it.inclusionProbability } shouldBe listOf(100, 90, 50)
                food.avoidanceSubstancesByProbability().first().substanceCode shouldBe AvoidanceSubstanceCodeRef("SOYBEAN")
            }
        }

        `when`("포함하는 기피 성분이 하나도 없으면") {
            then("빈 목록을 반환하고 음식은 유효하다") {
                val food = create(avoidanceSubstances = emptyList())

                food.avoidanceSubstancesByProbability() shouldBe emptyList()
                food.content.name.korean shouldBe "된장찌개"
            }
        }
    }

    given("Food.create — 포함 기피 성분 코드 유일") {
        `when`("한 음식에 같은 기피 성분 코드가 중복으로 담기면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    create(
                        avoidanceSubstances = listOf(
                            substance("SOYBEAN", 100),
                            substance("SOYBEAN", 80),
                        ),
                    )
                }
            }
        }
    }

    given("Food 콘텐츠 완성 상태") {
        `when`("정상 생성하면") {
            then("READY 이며 조회 가능하다") {
                create().contentStatus shouldBe FoodContentStatus.READY
                create().isReady() shouldBe true
            }
        }

        `when`("스캔 미스로 미완성 음식을 만들면") {
            then("한국어명만 갖고 INCOMPLETE 상태이며 성분이 비어 있다") {
                val food = Food.incomplete("우주라면")

                food.koreanName() shouldBe "우주라면"
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                food.isReady() shouldBe false
                food.avoidanceSubstances shouldBe emptyList()
            }
        }

        `when`("미완성 음식의 위험도를 물으면") {
            then("성분이 비어도 SAFE 가 아니라 UNKNOWN 이다") {
                Food.incomplete("우주라면").overallRisk(emptySet()) shouldBe RiskLevel.UNKNOWN
            }
        }

        `when`("한국어명이 blank 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { Food.incomplete(" ") }
            }
        }
    }

    given("Food.incomplete 한국어명 길이") {
        `when`("컬럼 길이(255)를 넘는 이름이면") {
            then("도메인이 거절한다(영속 계층 truncation 방지 최후 방어선)") {
                shouldThrow<IllegalArgumentException> {
                    Food.incomplete("가".repeat(KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH + 1))
                }
            }
        }
    }
})
