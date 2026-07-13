package com.meogo.domain.food

import com.meogo.core.lang.LanguageCode
import com.meogo.core.lang.LocalizedText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodContentTest : BehaviorSpec({
    val validName = "된장찌개"
    val validDescription = "구수한 한국식 된장찌개"

    fun content(
        name: String = validName,
        description: String = validDescription,
        nameTranslations: Map<LanguageCode, String> = emptyMap(),
        descriptionTranslations: Map<LanguageCode, String> = emptyMap(),
    ) = FoodContent(
        name = LocalizedText(korean = name, translations = nameTranslations),
        description = LocalizedText(korean = description, translations = descriptionTranslations),
    )

    given("FoodContent 생성 — 이름 제약") {
        `when`("이름이 빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(name = "") }
            }
        }

        `when`("이름이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(name = "   ") }
            }
        }

        `when`("이름이 255자와 같으면") {
            then("정상 생성되고 값을 보존한다") {
                content(name = "가".repeat(255)).name.korean shouldBe "가".repeat(255)
            }
        }

        `when`("이름이 256자를 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(name = "가".repeat(256)) }
            }
        }
    }

    given("FoodContent 생성 — 설명 제약") {
        `when`("설명이 빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(description = "") }
            }
        }

        `when`("설명이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(description = "   ") }
            }
        }

        `when`("설명이 255자와 같으면") {
            then("정상 생성되고 값을 보존한다") {
                content(description = "나".repeat(255)).description.korean shouldBe "나".repeat(255)
            }
        }

        `when`("설명이 256자를 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(description = "나".repeat(256)) }
            }
        }
    }

    given("이름 언어 해석 resolveName(lang)") {
        `when`("대상 언어가 KO 이면") {
            then("번역 맵을 조회하지 않고 korean 원문을 반환한다") {
                content(
                    name = "김치찌개",
                    nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).resolveName(LanguageCode.KO) shouldBe "김치찌개"
            }
        }

        `when`("대상 언어의 번역이 존재하면") {
            then("해당 번역값을 반환한다") {
                content(
                    name = "김치찌개",
                    nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).resolveName(LanguageCode.EN) shouldBe "Kimchi Stew"
            }
        }

        `when`("대상 언어의 키가 없고 다른 언어만 존재하면") {
            then("korean 으로 폴백한다") {
                content(
                    name = "김치찌개",
                    nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).resolveName(LanguageCode.RU) shouldBe "김치찌개"
            }
        }

        `when`("번역 맵이 비어 있으면") {
            then("어떤 대상 언어든 korean 으로 폴백한다") {
                content(name = "김치찌개").resolveName(LanguageCode.EN) shouldBe "김치찌개"
            }
        }
    }

    given("한국어 원문 이름 koreanName") {
        `when`("다른 언어 번역이 존재해도") {
            then("요청 언어와 무관하게 한국어 원문을 반환한다") {
                content(
                    name = "김치찌개",
                    nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).koreanName() shouldBe "김치찌개"
            }
        }

        `when`("번역 맵이 비어 있으면") {
            then("한국어 원문을 반환한다") {
                content(name = "김치찌개").koreanName() shouldBe "김치찌개"
            }
        }
    }

    given("설명 언어 해석 resolveDescription(lang)") {
        `when`("대상 언어가 KO 이면") {
            then("번역 맵을 조회하지 않고 korean 원문을 반환한다") {
                content(
                    description = "얼큰한 김치찌개",
                    descriptionTranslations = mapOf(LanguageCode.EN to "Spicy kimchi stew"),
                ).resolveDescription(LanguageCode.KO) shouldBe "얼큰한 김치찌개"
            }
        }

        `when`("대상 언어의 번역이 존재하면") {
            then("해당 번역값을 반환한다") {
                content(
                    description = "얼큰한 김치찌개",
                    descriptionTranslations = mapOf(LanguageCode.EN to "Spicy kimchi stew"),
                ).resolveDescription(LanguageCode.EN) shouldBe "Spicy kimchi stew"
            }
        }

        `when`("대상 언어의 키가 없고 다른 언어만 존재하면") {
            then("korean 으로 폴백한다") {
                content(
                    description = "얼큰한 김치찌개",
                    descriptionTranslations = mapOf(LanguageCode.EN to "Spicy kimchi stew"),
                ).resolveDescription(LanguageCode.RU) shouldBe "얼큰한 김치찌개"
            }
        }

        `when`("번역 맵이 비어 있으면") {
            then("어떤 대상 언어든 korean 으로 폴백한다") {
                content(description = "얼큰한 김치찌개").resolveDescription(LanguageCode.EN) shouldBe "얼큰한 김치찌개"
            }
        }
    }
})
