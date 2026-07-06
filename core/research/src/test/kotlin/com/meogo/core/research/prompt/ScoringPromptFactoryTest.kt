package com.meogo.core.research.prompt

import com.meogo.core.research.input.CandidateSubstance
import com.meogo.core.research.input.ScoringFood

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ScoringPromptFactoryTest : BehaviorSpec({

    val factory = ScoringPromptFactory()

    val foods = listOf(
        ScoringFood(foodId = 1L, koreanName = "김밥"),
        ScoringFood(foodId = 2L, koreanName = "불고기"),
    )
    val candidates = listOf(
        CandidateSubstance(code = "EGG", koreanLabel = "계란"),
        CandidateSubstance(code = "MILK", koreanLabel = "우유"),
    )

    fun text(prompt: ScoringPrompt): String = prompt.prompt + (prompt.system ?: "")

    given("음식·후보 성분이 주어진 스코어링 프롬프트 생성") {
        `when`("프롬프트를 빌드하면") {
            val prompt = factory.build(foods, candidates)
            val combined = text(prompt)

            then("대상 음식들의 한국어명을 포함한다") {
                combined shouldContain "김밥"
                combined shouldContain "불고기"
            }

            then("후보 성분의 code 와 ko 라벨을 포함한다") {
                combined shouldContain "EGG"
                combined shouldContain "계란"
                combined shouldContain "MILK"
                combined shouldContain "우유"
            }

            then("포함된 것만 응답하라는 지시를 포함한다") {
                combined shouldContain "포함"
            }

            then("score 0/1/2 정의를 고유 문구로 언급한다") {
                combined shouldContain "0=낮음"
                combined shouldContain "1=가능성 있음"
                combined shouldContain "2=높음"
            }

            then("probability 정수 1~100 강제를 범위 표현으로 언급한다") {
                combined shouldContain "1~100"
            }

            then("9개 대상 언어코드를 모두 언급한다") {
                listOf("zh-Hans", "en", "ja", "zh-Hant", "vi", "id", "th", "ru", "es").forEach {
                    combined shouldContain it
                }
            }

            then("음식 설명 생성(목표 200·최대 230자) 지시를 언급한다") {
                combined shouldContain "200"
                combined shouldContain "230"
            }
        }

        `when`("동일 입력으로 두 번 빌드하면") {
            val first = factory.build(foods, candidates)
            val second = factory.build(foods, candidates)

            then("동일한 프롬프트를 반환한다") {
                first.prompt shouldBe second.prompt
                first.system shouldBe second.system
            }
        }
    }

    given("빈 음식 목록") {
        `when`("프롬프트를 빌드하면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    factory.build(emptyList(), candidates)
                }
            }
        }
    }
})
