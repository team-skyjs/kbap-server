package com.kbap.infra.llm.menu

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class MenuBoardResultParserTest : BehaviorSpec({
    val parser = MenuBoardResultParser()

    given("메뉴판 비전 응답 JSON 파싱") {
        `when`("정상 형식의 results 를 받으면") {
            then("표기명·표준명·가격·매칭 idx 를 담은 목록으로 파싱한다") {
                val raw = """
                    {"results":[
                        {"name":"김치찌개","koreanName":"김치찌개","price":9000,"matchedIdx":0},
                        {"name":"Bulgogi 불고기","koreanName":"불고기","price":16000,"matchedIdx":3}
                    ]}
                """.trimIndent()

                val result = parser.parse(raw)

                result shouldHaveSize 2
                result[0].name shouldBe "김치찌개"
                result[0].priceKrw shouldBe 9000
                result[0].matchedIdx shouldBe 0
                result[1].name shouldBe "Bulgogi 불고기"
                result[1].koreanName shouldBe "불고기"
                result[1].matchedIdx shouldBe 3
            }
        }

        `when`("추출됐지만 대응하는 OCR 항목이 없어 matchedIdx 가 null 이면") {
            then("matchedIdx 를 null 로 파싱한다") {
                val raw = """{"results":[{"name":"라면","koreanName":"라면","price":4000,"matchedIdx":null}]}"""

                val result = parser.parse(raw)

                result shouldHaveSize 1
                result[0].matchedIdx.shouldBeNull()
            }
        }

        `when`("가격이 null 인 항목이 있으면") {
            then("priceKrw 를 null 로 파싱한다") {
                val raw = """{"results":[{"idx":1,"name":"공기밥","koreanName":"공기밥","price":null}]}"""

                val result = parser.parse(raw)

                result shouldHaveSize 1
                result[0].priceKrw.shouldBeNull()
            }
        }

        `when`("코드펜스로 감싼 JSON 을 받으면") {
            then("펜스를 제거하고 파싱한다") {
                val raw = "```json\n{\"results\":[{\"idx\":1,\"name\":\"라면\",\"koreanName\":\"라면\",\"price\":4000}]}\n```"

                parser.parse(raw) shouldHaveSize 1
            }
        }

        `when`("results 가 빈 배열이면(메뉴판 아닌 사진)") {
            then("빈 목록을 반환한다(실패 아님)") {
                parser.parse("""{"results":[]}""") shouldHaveSize 0
            }
        }

        `when`("이름이 빈 개별 항목이 섞여 있으면") {
            then("해당 항목만 건너뛴다") {
                val raw = """
                    {"results":[
                        {"idx":1,"name":"","koreanName":"","price":null},
                        {"idx":2,"name":"제육볶음","koreanName":"제육볶음","price":8000}
                    ]}
                """.trimIndent()

                val result = parser.parse(raw)

                result shouldHaveSize 1
                result[0].koreanName shouldBe "제육볶음"
            }
        }

        `when`("results 키가 없으면") {
            then("파싱 예외를 던진다(조용한 빈 결과 금지)") {
                shouldThrow<MenuBoardParseException> { parser.parse("""{"items":[]}""") }
            }
        }

        `when`("JSON 이 아니면") {
            then("파싱 예외를 던진다") {
                shouldThrow<MenuBoardParseException> { parser.parse("메뉴를 찾을 수 없습니다") }
            }
        }

        `when`("koreanName 없이 name 만 있는 항목이면(스캔 v2 프롬프트)") {
            then("koreanName 을 name 으로 채운다") {
                val raw = """{"results":[{"name":"김치찌개","price":9000}]}"""

                val result = MenuBoardResultParser().parse(raw)

                result shouldHaveSize 1
                result[0].name shouldBe "김치찌개"
                result[0].koreanName shouldBe "김치찌개"
                result[0].matchedIdx shouldBe null
            }
        }

        `when`("name 도 koreanName 도 없는 항목이면") {
            then("그 항목만 건너뛴다") {
                val raw = """{"results":[{"price":9000},{"name":"제육볶음","price":8000}]}"""

                val result = MenuBoardResultParser().parse(raw)

                result shouldHaveSize 1
                result[0].name shouldBe "제육볶음"
            }
        }
    }
})
