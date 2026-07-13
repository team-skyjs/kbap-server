package com.meogo.domain.research.prompt

import com.meogo.domain.research.input.CandidateSubstance
import com.meogo.domain.research.input.ScoringFood

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    given("음식·후보 성분이 주어진 압축 스코어링 프롬프트 생성") {
        `when`("프롬프트를 빌드하면") {
            val prompt = factory.build(foods, candidates)
            val system = prompt.system
            val combined = text(prompt)

            then("정적 프리픽스(system)에 후보 성분을 성분인덱스:CODE 로 코드만 열거한다") {
                system.shouldNotBeNull()
                system shouldContain "0:EGG"
                system shouldContain "1:MILK"
            }

            then("후보 열거에 한국어 라벨을 넣지 않는다") {
                combined shouldNotContain "계란"
                combined shouldNotContain "우유"
            }

            then("동적 부분(prompt)에 음식을 음식인덱스:한국어명 으로 0-base 열거한다") {
                prompt.prompt shouldContain "0:김밥"
                prompt.prompt shouldContain "1:불고기"
            }

            then("응답 스키마로 압축 배열 필드 c 와 r 을 지시한다") {
                system.shouldNotBeNull()
                system shouldContain "\"c\""
                system shouldContain "\"r\""
            }

            then("어떤 지시문에도 음식 설명(description) 지시가 없다") {
                combined.lowercase() shouldNotContain "description"
                combined shouldNotContain "230"
            }

            then("score 0/1/2·probability 정수 범위 의미를 유지한다") {
                combined shouldContain "0/1/2"
                combined shouldContain "1-100"
            }

            then("KB-53 key-value 스키마(results·nameTranslations 객체 키)를 쓰지 않는다") {
                combined shouldNotContain "\"results\""
                combined shouldNotContain "nameTranslations"
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

    given("이름 번역 변형(includeNameTranslations = true) 프롬프트 생성") {
        `when`("프롬프트를 빌드하면") {
            val prompt = factory.build(foods, candidates, includeNameTranslations = true)
            val combined = text(prompt)

            then("고정 언어 순서(zh-Hans..es, KO 제외)를 1회 선언한다") {
                combined shouldContain "zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es"
            }

            then("t 배열(음식인덱스, 9개 번역) 응답 스키마를 지시한다") {
                combined shouldContain "\"t\""
            }

            then("ko 번역 키는 지시하지 않는다") {
                combined shouldNotContain "\"ko\""
            }

            then("음식 설명(description) 지시는 어느 변형에도 없다") {
                combined.lowercase() shouldNotContain "description"
            }
        }
    }

    given("스코어링 전용 변형(includeNameTranslations = false) 프롬프트 생성") {
        `when`("프롬프트를 빌드하면") {
            val prompt = factory.build(foods, candidates, includeNameTranslations = false)
            val combined = text(prompt)

            then("이름 번역(t)·고정 언어 순서 지시가 없다") {
                combined shouldNotContain "\"t\""
                combined shouldNotContain "zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es"
            }

            then("음식 설명(description) 지시도 없다") {
                combined.lowercase() shouldNotContain "description"
            }
        }
    }

    given("같은 후보로 서로 다른 음식 청크 2개를 빌드(프리픽스 캐싱 정렬)") {
        val chunkA = listOf(
            ScoringFood(foodId = 1L, koreanName = "김밥"),
            ScoringFood(foodId = 2L, koreanName = "불고기"),
        )
        val chunkB = listOf(
            ScoringFood(foodId = 3L, koreanName = "비빔밥"),
            ScoringFood(foodId = 4L, koreanName = "떡볶이"),
        )

        `when`("스코어링 전용 변형으로 두 청크를 빌드하면") {
            val a = factory.build(chunkA, candidates, includeNameTranslations = false)
            val b = factory.build(chunkB, candidates, includeNameTranslations = false)

            then("system(정적 프리픽스)은 청크와 무관하게 바이트 동일하다") {
                a.system shouldBe b.system
            }

            then("prompt(동적 부분)은 청크마다 달라진다") {
                a.prompt shouldNotBe b.prompt
            }
        }

        `when`("이름 번역 변형으로 두 청크를 빌드하면") {
            val a = factory.build(chunkA, candidates, includeNameTranslations = true)
            val b = factory.build(chunkB, candidates, includeNameTranslations = true)

            then("system 은 이름 번역 변형에서도 청크 무관 바이트 동일하다") {
                a.system shouldBe b.system
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
