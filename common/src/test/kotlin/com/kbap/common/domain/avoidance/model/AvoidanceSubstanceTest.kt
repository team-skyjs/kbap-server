package com.kbap.common.domain.avoidance.model

import com.kbap.common.core.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

class AvoidanceSubstanceTest : BehaviorSpec({
    fun substance(
        code: AvoidanceSubstanceCode = AvoidanceSubstanceCode.PEANUT,
        koreanName: String = "땅콩",
        translations: Map<String, String> = emptyMap(),
    ): AvoidanceSubstance =
        AvoidanceSubstance(code = code, koreanName = koreanName, translations = translations)

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
                substance(koreanName = "계란", translations = mapOf("en" to "Egg"))
                    .displayName(LanguageCode.EN) shouldBe "Egg"
            }
        }

        `when`("요청 언어의 번역이 없으면") {
            then("한국어명으로 폴백한다") {
                substance(koreanName = "계란", translations = mapOf("en" to "Egg"))
                    .displayName(LanguageCode.JA) shouldBe "계란"
            }
        }

        `when`("미인식 언어 키가 섞여 있으면") {
            then("언어 해석에서 무시된다") {
                substance(koreanName = "밀", translations = mapOf("en" to "Wheat", "xx" to "무시대상"))
                    .displayName(LanguageCode.EN) shouldBe "Wheat"
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
