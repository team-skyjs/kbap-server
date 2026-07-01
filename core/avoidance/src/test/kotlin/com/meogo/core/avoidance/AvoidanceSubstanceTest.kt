package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.BehaviorSpec
import java.lang.reflect.Modifier

class AvoidanceSubstanceTest : BehaviorSpec({
    fun substance(
        id: Long = 1L,
        code: AvoidanceSubstanceCode = AvoidanceSubstanceCode.PEANUT,
        koreanName: String = "땅콩",
        translations: Map<LanguageCode, String> = emptyMap(),
        categories: Set<AvoidanceCategory> = setOf(AvoidanceCategory.ALLERGEN),
    ): AvoidanceSubstance =
        AvoidanceSubstance.reconstitute(
            id = id,
            code = code,
            koreanName = koreanName,
            translations = translations,
            categories = categories,
        )

    given("성분 어그리게이트 복원") {
        `when`("복원에 사용한 값으로 조회하면") {
            then("코드·한국어명·번역·분류를 그대로 보유한다") {
                val restored = substance(
                    id = 7L,
                    code = AvoidanceSubstanceCode.EGG,
                    koreanName = "계란",
                    translations = mapOf(LanguageCode.EN to "Egg", LanguageCode.JA to "卵"),
                    categories = setOf(AvoidanceCategory.ALLERGEN, AvoidanceCategory.DIETARY_RULE),
                )

                restored.id shouldBe 7L
                restored.code shouldBe AvoidanceSubstanceCode.EGG
                restored.koreanName shouldBe "계란"
                restored.translations shouldBe mapOf(LanguageCode.EN to "Egg", LanguageCode.JA to "卵")
                restored.categories shouldContainExactlyInAnyOrder listOf(
                    AvoidanceCategory.ALLERGEN,
                    AvoidanceCategory.DIETARY_RULE,
                )
            }
        }
    }

    given("표시명 displayName") {
        `when`("언어가 KO 이면") {
            then("저장된 한국어명을 반환한다") {
                substance(koreanName = "땅콩").displayName(LanguageCode.KO) shouldBe "땅콩"
            }
            then("코드가 같아도 한국어명이 다르면 저장된 한국어명을 반환한다") {
                substance(code = AvoidanceSubstanceCode.PEANUT, koreanName = "땅콩-운영자수정")
                    .displayName(LanguageCode.KO) shouldBe "땅콩-운영자수정"
            }
        }

        `when`("요청 언어의 번역이 있으면") {
            then("그 언어의 번역을 반환한다") {
                substance(koreanName = "계란", translations = mapOf(LanguageCode.EN to "Egg"))
                    .displayName(LanguageCode.EN) shouldBe "Egg"
            }
        }

        `when`("요청 언어의 번역이 없으면") {
            then("한국어명으로 폴백한다") {
                substance(koreanName = "계란", translations = mapOf(LanguageCode.EN to "Egg"))
                    .displayName(LanguageCode.JA) shouldBe "계란"
            }
        }
    }

    given("분류 소속 belongsTo") {
        val restored = substance(
            categories = setOf(AvoidanceCategory.ALLERGEN, AvoidanceCategory.DIETARY_RULE),
        )

        `when`("자신의 분류 집합에 속한 분류를 물으면") {
            then("참을 반환한다") {
                restored.belongsTo(AvoidanceCategory.ALLERGEN) shouldBe true
                restored.belongsTo(AvoidanceCategory.DIETARY_RULE) shouldBe true
            }
        }

        `when`("자신의 분류 집합에 없는 분류를 물으면") {
            then("거짓을 반환한다") {
                restored.belongsTo(AvoidanceCategory.PERSONAL_AVOIDANCE) shouldBe false
            }
        }
    }

    given("어그리게이트 불변식") {
        `when`("분류 집합이 비어 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    substance(categories = emptySet())
                }
            }
        }

        `when`("한국어명이 blank 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    substance(koreanName = "  ")
                }
            }
        }
    }

    given("성분 코드 식별자 enum") {
        `when`("전체 코드를 조회하면") {
            then("정확히 81종이다") {
                AvoidanceSubstanceCode.entries.size shouldBe 81
            }
            then("코드는 모두 유일하다") {
                AvoidanceSubstanceCode.entries.map { it.name }.distinct().size shouldBe 81
            }
        }

        `when`("enum 의 선언 필드를 리플렉션으로 확인하면") {
            then("데이터 인스턴스 필드를 갖지 않는다") {
                val instanceFields = AvoidanceSubstanceCode::class.java.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }

                instanceFields shouldBe emptyList()
            }
        }
    }

    given("회피·주의 분류 enum") {
        `when`("전체 분류를 조회하면") {
            then("정확히 세 값 ALLERGEN/DIETARY_RULE/PERSONAL_AVOIDANCE 이다") {
                AvoidanceCategory.entries shouldContainExactly listOf(
                    AvoidanceCategory.ALLERGEN,
                    AvoidanceCategory.DIETARY_RULE,
                    AvoidanceCategory.PERSONAL_AVOIDANCE,
                )
            }
        }
    }
})
