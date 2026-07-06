package com.meogo.core.research

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ScoringResponseParserTest : BehaviorSpec({

    val parser = ScoringResponseParser()

    val foods = listOf(
        ScoringFood(foodId = 1L, koreanName = "김밥"),
        ScoringFood(foodId = 2L, koreanName = "불고기"),
    )
    val candidates = listOf(
        CandidateSubstance(code = "EGG", koreanLabel = "계란"),
        CandidateSubstance(code = "MILK", koreanLabel = "우유"),
        CandidateSubstance(code = "WHEAT", koreanLabel = "밀"),
    )

    given("포함된 성분만 담긴 정상 부분응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[
                {"code":"EGG","score":2,"probability":90},
                {"code":"MILK","score":1,"probability":50}
              ]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("응답에 포함된 (food, code) 판단만 담기고 값 범위가 유효하다") {
                val judgements = result.included[1L]
                judgements.shouldNotBeNull()
                judgements.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG", "MILK")
                val egg = judgements.first { it.code == "EGG" }
                egg.score shouldBe 2
                egg.probability shouldBe 90
            }
        }
    }

    given("일부 성분이 응답에서 누락된 부분응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":30}]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("응답에 없는 성분은 included 리스트에 담기지 않는다") {
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }

            then("included 항목이 없는 음식은 맵에 담기지 않는다") {
                result.included shouldNotContainKey 2L
            }
        }
    }

    given("후보 밖 미지 코드가 섞인 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[
                {"code":"UNKNOWN","score":2,"probability":80},
                {"code":"EGG","score":1,"probability":40}
              ]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("미지 코드는 버리고 유효 판단은 유지한다") {
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }
        }
    }

    given("score 가 0..2 범위를 벗어난 판단이 섞인 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[
                {"code":"EGG","score":5,"probability":80},
                {"code":"MILK","score":1,"probability":40}
              ]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("범위 밖 score 판단만 버리고 나머지는 유지한다") {
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("probability 가 1..100 범위를 벗어난 판단이 섞인 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[
                {"code":"EGG","score":2,"probability":0},
                {"code":"MILK","score":1,"probability":40}
              ]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("범위 밖 probability 판단만 버리고 나머지는 유지한다") {
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("score 가 비수치 문자열인 판단이 섞인 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[
                {"code":"EGG","score":"high","probability":80},
                {"code":"MILK","score":1,"probability":40}
              ]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("비수치 판단만 버리고 나머지는 유지한다") {
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("동일 (food, code) 판단이 중복된 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[
                {"code":"EGG","score":2,"probability":90},
                {"code":"EGG","score":1,"probability":10}
              ]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("첫 유효 판단만 채택한다") {
                val judgements = result.included[1L]!!
                judgements.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
                val egg = judgements.first { it.code == "EGG" }
                egg.score shouldBe 2
                egg.probability shouldBe 90
            }
        }
    }

    given("청크에 없는 음식명이 담긴 응답") {
        val content = """
            {"results":[
              {"food":"짜장면","included":[{"code":"WHEAT","score":2,"probability":95}]},
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("미지 음식 결과 항목은 skip 하고 유효 음식만 담는다") {
                result.included.keys shouldContainExactlyInAnyOrder listOf(1L)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }
        }
    }

    given("다양한 언어 키를 가진 nameTranslations 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}],
               "nameTranslations":{"en":"Gimbap","ja":"キンパ","fr":"Gimbap","ko":"김밥","th":""}}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("유효 언어만 LanguageCode 로 매핑해 담는다") {
                val translations = result.nameTranslations[1L]
                translations.shouldNotBeNull()
                translations.keys shouldContainExactlyInAnyOrder listOf(LanguageCode.EN, LanguageCode.JA)
                translations[LanguageCode.EN] shouldBe "Gimbap"
            }

            then("미지 언어·ko·빈값 번역은 skip 하고 성분 파싱은 유지한다") {
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }
        }
    }

    given("nameTranslations 객체가 깨진 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}],
               "nameTranslations":"not-an-object"}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("해당 음식의 nameTranslations 는 빈 맵이고 성분 파싱은 유지한다") {
                (result.nameTranslations[1L] ?: emptyMap()) shouldBe emptyMap()
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }
        }
    }

    given("정상 description 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}],
               "description":{"ko":"밥과 채소를 김으로 감싼 음식","translations":{"en":"Rice rolled in seaweed","fr":"x","th":""}}}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("ko 원문을 담고 유효 번역만 매핑한다") {
                val description = result.descriptions[1L]
                description.shouldNotBeNull()
                description.korean shouldBe "밥과 채소를 김으로 감싼 음식"
                description.translations.keys shouldContainExactlyInAnyOrder listOf(LanguageCode.EN)
            }
        }
    }

    given("ko 설명이 공백 포함 230자를 초과하는 응답") {
        val longKo = "가".repeat(250)
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}],
               "description":{"ko":"$longKo","translations":{}}}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("앞 230자로 잘라낸다") {
                val description = result.descriptions[1L]
                description.shouldNotBeNull()
                description.korean.length shouldBe 230
                description.korean shouldBe longKo.substring(0, 230)
            }
        }
    }

    given("description 이 없는 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}]}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("해당 음식 설명은 맵에 담기지 않는다") {
                result.descriptions shouldNotContainKey 1L
            }
        }
    }

    given("description 에 ko 가 없는 응답") {
        val content = """
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":1,"probability":40}],
               "description":{"translations":{"en":"Rice rolled in seaweed"}}}
            ]}
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("해당 음식 설명은 skip 한다") {
                result.descriptions shouldNotContainKey 1L
            }
        }
    }

    given("코드펜스로 감싼 응답") {
        val content = """
            ```json
            {"results":[
              {"food":"김밥","included":[{"code":"EGG","score":2,"probability":90}]}
            ]}
            ```
        """.trimIndent()

        `when`("파싱하면") {
            val result = parser.parse(content, foods, candidates)

            then("코드펜스를 스트립하고 파싱한다") {
                result.included shouldContainKey 1L
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }
        }
    }

    given("빈 문자열 content") {
        `when`("파싱하면") {
            then("ScoringResponseParseException 을 던진다") {
                shouldThrow<ScoringResponseParseException> {
                    parser.parse("", foods, candidates)
                }
            }
        }
    }

    given("깨진 JSON content") {
        `when`("파싱하면") {
            then("ScoringResponseParseException 을 던진다") {
                shouldThrow<ScoringResponseParseException> {
                    parser.parse("{results: [", foods, candidates)
                }
            }
        }
    }
})
