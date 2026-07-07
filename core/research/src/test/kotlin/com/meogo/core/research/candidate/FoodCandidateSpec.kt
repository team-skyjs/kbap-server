package com.meogo.core.research.candidate

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodCandidateSpec : BehaviorSpec({

    val targetTranslations: Map<LanguageCode, String> =
        (LanguageCode.entries.toSet() - LanguageCode.KO).associateWith { "설명-${it.code}" }

    val oneSubstance = listOf(SubstanceSnapshot(code = "PEANUT", inclusionPercent = 30))

    given("완성 조건을 모두 갖춘 FoodCandidate") {
        `when`("성분 매핑이 비어 있으면") {
            then("isComplete 는 false 다") {
                val candidate = FoodCandidate.create(
                    koreanName = "김치찌개",
                    koreanDescription = "돼지고기와 김치로 끓인 찌개",
                    descriptionTranslations = targetTranslations,
                    substanceMapping = emptyList(),
                )
                candidate.isComplete() shouldBe false
            }
        }

        `when`("koreanDescription 이 null 이면") {
            then("isComplete 는 false 다") {
                val candidate = FoodCandidate.create(
                    koreanName = "김치찌개",
                    koreanDescription = null,
                    descriptionTranslations = targetTranslations,
                    substanceMapping = oneSubstance,
                )
                candidate.isComplete() shouldBe false
            }
        }

        `when`("성분·ko설명·번역9개가 모두 충족되고 미승격이면") {
            then("isComplete 는 true 다") {
                val candidate = FoodCandidate.create(
                    koreanName = "김치찌개",
                    koreanDescription = "돼지고기와 김치로 끓인 찌개",
                    descriptionTranslations = targetTranslations,
                    substanceMapping = oneSubstance,
                )
                candidate.isComplete() shouldBe true
            }
        }

        `when`("번역이 8개로 하나 누락되면") {
            then("isComplete 는 false 다") {
                val missingOne = targetTranslations.entries.drop(1).associate { it.key to it.value }
                val candidate = FoodCandidate.create(
                    koreanName = "김치찌개",
                    koreanDescription = "돼지고기와 김치로 끓인 찌개",
                    descriptionTranslations = missingOne,
                    substanceMapping = oneSubstance,
                )
                candidate.isComplete() shouldBe false
            }
        }

        `when`("이미 publishedFoodId 가 설정되어 있으면") {
            then("isComplete 는 false 다") {
                val candidate = FoodCandidate.reconstitute(
                    id = 1L,
                    koreanName = "김치찌개",
                    koreanDescription = "돼지고기와 김치로 끓인 찌개",
                    descriptionTranslations = targetTranslations,
                    substanceMapping = oneSubstance,
                    publishedFoodId = 42L,
                )
                candidate.isComplete() shouldBe false
            }
        }
    }

    given("FoodCandidate 생성 검증") {
        `when`("koreanName 이 blank 이면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    FoodCandidate.create(
                        koreanName = "   ",
                        koreanDescription = "설명",
                        descriptionTranslations = targetTranslations,
                        substanceMapping = oneSubstance,
                    )
                }
            }
        }

        `when`("번역 맵에 KO 등 비지원 키가 포함되면") {
            then("IllegalArgumentException 을 던진다") {
                val withKo = targetTranslations + (LanguageCode.KO to "한국어 원문")
                shouldThrow<IllegalArgumentException> {
                    FoodCandidate.create(
                        koreanName = "김치찌개",
                        koreanDescription = "설명",
                        descriptionTranslations = withKo,
                        substanceMapping = oneSubstance,
                    )
                }
            }
        }
    }

    given("SubstanceSnapshot 확률 검증") {
        `when`("inclusionPercent 가 0 이면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    SubstanceSnapshot(code = "PEANUT", inclusionPercent = 0)
                }
            }
        }

        `when`("inclusionPercent 가 101 이면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    SubstanceSnapshot(code = "PEANUT", inclusionPercent = 101)
                }
            }
        }

        `when`("inclusionPercent 가 경계값 1 과 100 이면") {
            then("예외 없이 생성된다") {
                shouldNotThrowAny {
                    SubstanceSnapshot(code = "PEANUT", inclusionPercent = 1)
                    SubstanceSnapshot(code = "PEANUT", inclusionPercent = 100)
                }
            }
        }
    }
})
