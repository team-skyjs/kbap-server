package com.meogo.infra.llm.menu

import com.meogo.core.kernel.scan.InterpretedName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ScannedNameParserTest : BehaviorSpec({
    val parser = ScannedNameParser()

    given("ScannedNameParser — LLM 배열 응답 파싱") {
        `when`("표준명과 NOT_FOOD 가 섞인 JSON 배열이면") {
            then("입력 순서대로 InterpretedName 으로 매핑한다") {
                val result = parser.parse("""["김치찌개","NOT_FOOD","돈까스"]""", expectedSize = 3)
                result shouldBe listOf(
                    InterpretedName.StandardName("김치찌개"),
                    InterpretedName.NotFood,
                    InterpretedName.StandardName("돈까스"),
                )
            }
        }

        `when`("응답이 마크다운 코드펜스로 감싸져 있으면") {
            then("펜스를 벗기고 파싱한다") {
                val fenced = "```json\n[\"김치찌개\"]\n```"
                parser.parse(fenced, expectedSize = 1) shouldBe listOf(InterpretedName.StandardName("김치찌개"))
            }
        }

        `when`("표준명이 빈 문자열이면") {
            then("NotFood 로 취급한다") {
                parser.parse("""["",""]""", expectedSize = 2) shouldBe listOf(
                    InterpretedName.NotFood,
                    InterpretedName.NotFood,
                )
            }
        }

        `when`("JSON 이 아니면") {
            then("파싱 예외를 던진다") {
                shouldThrow<ScannedNameParseException> { parser.parse("보통 텍스트", expectedSize = 1) }
            }
        }

        `when`("배열 길이가 기대 개수와 다르면") {
            then("파싱 예외를 던진다") {
                shouldThrow<ScannedNameParseException> {
                    parser.parse("""["김치찌개"]""", expectedSize = 2)
                }
            }
        }
    }
})
