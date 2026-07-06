package com.meogo.core.research.prompt

import com.meogo.core.research.input.CandidateSubstance
import com.meogo.core.research.input.ScoringFood

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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

            then("지시문은 영어로 작성한다") {
                combined shouldContain "representative recipe"
                combined shouldNotContain "대표 레시피"
            }

            then("포함 판단한 것만 응답하라는 지시를 포함한다") {
                combined shouldContain "judge as included"
                combined shouldContain "omit"
            }

            then("score 0/1/2 정의를 고유 문구로 언급한다") {
                combined shouldContain "0=low"
                combined shouldContain "1=possible"
                combined shouldContain "2=high"
            }

            then("probability 정수 1~100 강제를 범위 표현으로 언급한다") {
                combined shouldContain "1-100"
            }

            then("후보 목록 외 코드 창작 금지를 강제 문구로 언급한다") {
                combined shouldContain "verbatim"
                combined shouldContain "NEVER invent"
            }

            then("9개 대상 언어코드를 모두 언급한다") {
                listOf("zh-Hans", "en", "ja", "zh-Hant", "vi", "id", "th", "ru", "es").forEach {
                    combined shouldContain it
                }
            }

            then("9개 언어 전부 필수·생략 금지 지시를 언급한다") {
                combined shouldContain "ALL 9 target languages"
                combined shouldContain "Do NOT omit any language"
            }

            then("마크다운 코드펜스 금지를 언급한다") {
                combined shouldContain "code fences"
            }

            then("모든 음식 entry 필수(빈 included 허용) 커버리지 지시를 언급한다") {
                combined shouldContain "one entry for EVERY food"
                combined shouldContain "\"included\": []"
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
