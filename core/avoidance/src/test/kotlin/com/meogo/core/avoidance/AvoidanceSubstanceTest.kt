package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.BehaviorSpec
import java.lang.reflect.Modifier

class AvoidanceSubstanceTest : BehaviorSpec({
    fun substance(
        id: Long = 1L,
        code: AvoidanceSubstanceCode = AvoidanceSubstanceCode.PEANUT,
        koreanName: String = "땅콩",
        translations: Map<LanguageCode, String> = emptyMap(),
    ): AvoidanceSubstance =
        AvoidanceSubstance.reconstitute(
            id = id,
            code = code,
            koreanName = koreanName,
            translations = translations,
        )

    given("성분 어그리게이트 복원") {
        `when`("복원에 사용한 값으로 조회하면") {
            then("코드·한국어명·번역을 그대로 보유한다") {
                val restored = substance(
                    id = 7L,
                    code = AvoidanceSubstanceCode.EGG,
                    koreanName = "계란",
                    translations = mapOf(LanguageCode.EN to "Egg", LanguageCode.JA to "卵"),
                )

                restored.id shouldBe 7L
                restored.code shouldBe AvoidanceSubstanceCode.EGG
                restored.koreanName shouldBe "계란"
                restored.translations shouldBe mapOf(LanguageCode.EN to "Egg", LanguageCode.JA to "卵")
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

    given("성분 동등성") {
        `when`("코드가 같고 한국어명·번역이 다른 두 인스턴스를 비교하면") {
            then("code 기준으로 동등하다") {
                val a = substance(id = 1L, code = AvoidanceSubstanceCode.SOY, koreanName = "대두")
                val b = substance(id = 2L, code = AvoidanceSubstanceCode.SOY, koreanName = "대두-운영자수정")

                (a == b) shouldBe true
                (a.hashCode() == b.hashCode()) shouldBe true
            }
        }

        `when`("코드가 다른 두 인스턴스를 비교하면") {
            then("동등하지 않다") {
                val soy = substance(code = AvoidanceSubstanceCode.SOY, koreanName = "대두")
                val egg = substance(code = AvoidanceSubstanceCode.EGG, koreanName = "계란")

                (soy == egg) shouldBe false
            }
        }
    }

    given("어그리게이트 불변식") {
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
            then("개발 가독성 label 만 허용하고 콘텐츠 데이터(번역·분류 등)는 갖지 않는다") {
                val instanceFieldNames = AvoidanceSubstanceCode::class.java.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .map { it.name }

                instanceFieldNames shouldBe listOf("label")
            }
        }
    }
})
