package com.kbap.common.domain.food.model

import com.kbap.common.core.lang.LanguageCode
import com.kbap.common.core.menu.KoreanMenuNameNormalizer
import com.kbap.common.core.risk.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodTest : BehaviorSpec({
    fun item(code: String, percent: Int) =
        FoodAvoidanceItem(code = code, inclusionPercent = percent)

    fun create(
        koreanName: String = "된장찌개",
        description: String = "구수한 한국식 된장찌개",
        spiciness: Int = 3,
        nameTranslations: Map<String, String> = emptyMap(),
        descriptionTranslations: Map<String, String> = emptyMap(),
        avoidanceSubstances: List<FoodAvoidanceItem> = listOf(item("SOYBEAN", 100)),
    ) = Food(
        koreanName = koreanName,
        description = description,
        spiciness = spiciness,
        nameTranslations = nameTranslations,
        descriptionTranslations = descriptionTranslations,
        avoidanceSubstances = avoidanceSubstances,
    )

    given("Food — 구성·맵기 보존") {
        `when`("정상 값으로 생성하면") {
            then("이름·설명·맵기를 그대로 보존한다") {
                val food = create()

                food.koreanName() shouldBe "된장찌개"
                food.description shouldBe "구수한 한국식 된장찌개"
                food.spiciness shouldBe 3
            }
        }
    }

    given("Food.displayName / description — 요청 언어 표시값(ko 폴백)") {
        val localized = create(
            koreanName = "된장찌개",
            description = "구수한 된장찌개",
            nameTranslations = mapOf("en" to "Doenjang Stew"),
            descriptionTranslations = mapOf("en" to "A hearty stew"),
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
            then("한국어 원문 이름을 반환한다") {
                val food = create(nameTranslations = mapOf("en" to "Doenjang Stew"))

                food.koreanName() shouldBe "된장찌개"
            }
        }
    }

    given("FoodAvoidanceItem.riskLevel — 포함 확률로 위험도 산출") {
        `when`("포함 확률이 DANGER 임계(60) 이상이면") {
            then("DANGER 다") {
                item("SOY", 100).riskLevel() shouldBe RiskLevel.DANGER
                item("SOY", 60).riskLevel() shouldBe RiskLevel.DANGER
            }
        }

        `when`("포함 확률이 CAUTION 구간(10~59)이면") {
            then("CAUTION 이다") {
                item("CLAM", 50).riskLevel() shouldBe RiskLevel.CAUTION
                item("CLAM", 10).riskLevel() shouldBe RiskLevel.CAUTION
            }
        }

        `when`("포함 확률이 CAUTION 임계 미만(1~9)이면") {
            then("SAFE 다") {
                item("TRACE", 5).riskLevel() shouldBe RiskLevel.SAFE
            }
        }
    }

    given("Food.avoidanceSubstancesByProbability") {
        `when`("포함 성분이 포함 확률 내림차순이 아닌 순서로 담겨 있으면") {
            then("저장 순서와 무관하게 포함 확률 내림차순으로 정렬된 성분을 반환한다") {
                val food = create(
                    avoidanceSubstances = listOf(
                        item("CLAM", 50),
                        item("SOYBEAN", 100),
                        item("TOFU", 80),
                    ),
                )

                food.avoidanceSubstancesByProbability().map { it.inclusionPercent } shouldBe listOf(100, 80, 50)
                food.avoidanceSubstancesByProbability().map { it.code } shouldBe listOf("SOYBEAN", "TOFU", "CLAM")
            }
        }

        `when`("포함하는 기피 성분이 하나도 없으면") {
            then("빈 목록을 반환하고 음식은 유효하다") {
                val food = create(avoidanceSubstances = emptyList())

                food.avoidanceSubstancesByProbability() shouldBe emptyList()
                food.koreanName() shouldBe "된장찌개"
            }
        }
    }

    given("Food.updateNameTranslations — 이름 번역 전수 교체") {
        val allNameTargets = LanguageCode.entries.filter { it != LanguageCode.KO }
            .associate { it.code to "name-${it.code}" }

        `when`("이름 번역이 없는 음식에 9개 전수를 반영하면") {
            then("이름 번역이 채워지고 미완 판정이 해소되며 원문 이름은 그대로다") {
                val food = Food.incomplete("우주라면")

                food.updateNameTranslations(allNameTargets)

                food.nameTranslations shouldBe allNameTargets
                food.needsNameTranslations() shouldBe false
                food.koreanName() shouldBe "우주라면"
            }
        }

        `when`("일부 언어만 있던 음식에 새 전수를 반영하면") {
            then("병합 없이 전체 교체된다") {
                val food = create(nameTranslations = mapOf("en" to "Old Name"))

                food.updateNameTranslations(allNameTargets)

                food.nameTranslations shouldBe allNameTargets
            }
        }
    }

    given("Food.updateDescription — 설명 원문·번역 세트 교체") {
        val allTargets = LanguageCode.entries.filter { it != LanguageCode.KO }
            .associate { it.code to "desc-${it.code}" }

        `when`("플레이스홀더 설명 음식에 원문과 9개 번역을 반영하면") {
            then("설명·번역이 함께 채워지고 미완 판정이 해소된다") {
                val food = Food.incomplete("우주라면")

                food.updateDescription("우주 맛 라면", allTargets)

                food.description shouldBe "우주 맛 라면"
                food.descriptionTranslations shouldBe allTargets
                food.needsDescription() shouldBe false
                food.needsDescriptionTranslations() shouldBe false
            }
        }

        `when`("일부 언어 번역만 있던 음식에 새 세트를 반영하면") {
            then("병합 없이 새 원문·번역 세트로 전체 교체된다") {
                val food = create(
                    description = "옛 설명",
                    descriptionTranslations = mapOf("en" to "old description"),
                )

                food.updateDescription("새 설명", allTargets)

                food.description shouldBe "새 설명"
                food.descriptionTranslations shouldBe allTargets
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
            then("한국어명만 갖고 INCOMPLETE 상태이며 성분은 미조사(null)다") {
                val food = Food.incomplete("우주라면")

                food.koreanName() shouldBe "우주라면"
                food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                food.isReady() shouldBe false
                food.avoidanceSubstances shouldBe null
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
