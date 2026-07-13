package com.meogo.domain.research.parse

import com.meogo.core.lang.LanguageCode
import com.meogo.domain.research.input.CandidateSubstance
import com.meogo.domain.research.input.ScoringFood

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

    given("포함 판단만 담긴 정상 압축 응답") {
        val content = """{"c":[0,1],"r":[[0,0,2,90],[0,1,1,50]]}"""

        `when`("파싱하면") {
            then("배열 항목을 인덱스로 음식·성분에 되짚어 담고 값 범위가 유효하다") {
                val result = parser.parse(content, foods, candidates)
                val judgements = result.included[1L]
                judgements.shouldNotBeNull()
                judgements.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG", "MILK")
                val egg = judgements.first { it.code == "EGG" }
                egg.score shouldBe 2
                egg.probability shouldBe 90
            }
        }
    }

    given("일부 성분만 r 에 담긴 압축 응답") {
        val content = """{"c":[0,1],"r":[[0,0,1,30]]}"""

        `when`("파싱하면") {
            then("응답에 없는 성분은 included 리스트에 담기지 않는다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }

            then("r 에 포함 항목이 없는 음식은 included 맵에 담기지 않는다") {
                val result = parser.parse(content, foods, candidates)
                result.included shouldNotContainKey 2L
            }
        }
    }

    given("c 와 r 의 음식 인덱스가 서로 다른 응답") {
        val content = """{"c":[0],"r":[[1,2,2,95]]}"""

        `when`("파싱하면") {
            then("coveredFoodIds 는 c 유효 인덱스와 r 항목 음식 인덱스의 합집합이다") {
                val result = parser.parse(content, foods, candidates)
                result.coveredFoodIds shouldBe setOf(1L, 2L)
            }

            then("r 항목의 판단은 해당 음식에 담긴다") {
                val result = parser.parse(content, foods, candidates)
                result.included[2L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("WHEAT")
            }
        }
    }

    given("항목이 4-원소 정수 배열이 아닌 응답") {
        val content = """{"c":[0],"r":[[0,0,2],[0,1,1,40]]}"""

        `when`("파싱하면") {
            then("형식 오류 항목만 스킵하고 나머지는 유지한다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("항목 원소가 비정수인 응답") {
        val content = """{"c":[0],"r":[[0,0,"x",90],[0,1,1,40]]}"""

        `when`("파싱하면") {
            then("비정수 항목만 스킵하고 나머지는 유지한다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("음식·성분 인덱스가 범위를 벗어난 항목이 섞인 응답") {
        val content = """{"c":[0],"r":[[5,0,2,90],[0,5,1,40],[0,1,1,50]]}"""

        `when`("파싱하면") {
            then("범위 이탈 항목만 스킵하고 유효 항목은 유지한다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("score 가 0..2 범위를 벗어난 항목이 섞인 응답") {
        val content = """{"c":[0],"r":[[0,0,5,80],[0,1,1,40]]}"""

        `when`("파싱하면") {
            then("범위 밖 score 항목만 스킵하고 나머지는 유지한다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("probability 가 1..100 범위를 벗어난 항목이 섞인 응답") {
        val content = """{"c":[0],"r":[[0,0,2,0],[0,1,1,40]]}"""

        `when`("파싱하면") {
            then("범위 밖 probability 항목만 스킵하고 나머지는 유지한다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("동일 (음식, 성분) 인덱스가 중복된 응답") {
        val content = """{"c":[0],"r":[[0,0,2,90],[0,0,1,10]]}"""

        `when`("파싱하면") {
            then("첫 유효 판단만 채택한다") {
                val result = parser.parse(content, foods, candidates)
                val judgements = result.included[1L]!!
                judgements.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
                val egg = judgements.first { it.code == "EGG" }
                egg.score shouldBe 2
                egg.probability shouldBe 90
            }
        }
    }

    given("코드펜스로 감싼 압축 응답") {
        val content = """
            ```json
            {"c":[0],"r":[[0,0,2,90]]}
            ```
        """.trimIndent()

        `when`("파싱하면") {
            then("코드펜스를 스트립하고 파싱한다") {
                val result = parser.parse(content, foods, candidates)
                result.included shouldContainKey 1L
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("EGG")
            }
        }
    }

    given("c 에 범위를 벗어난 음식 인덱스가 섞인 응답") {
        val content = """{"c":[0,9],"r":[]}"""

        `when`("파싱하면") {
            then("범위 밖 c 인덱스는 coveredFoodIds 에서 제외한다") {
                val result = parser.parse(content, foods, candidates)
                result.coveredFoodIds shouldBe setOf(1L)
            }
        }
    }

    given("r 이 빈 배열이고 c 가 청크 전체를 attest 한 응답") {
        val content = """{"c":[0,1],"r":[]}"""

        `when`("파싱하면") {
            then("포함 판단은 비어 있고 coveredFoodIds 는 c 전체를 담는다") {
                val result = parser.parse(content, foods, candidates)
                result.included shouldBe emptyMap()
                result.coveredFoodIds shouldBe setOf(1L, 2L)
            }
        }
    }

    given("t(이름 번역) 필드가 없는 스코어링 전용 응답") {
        val content = """{"c":[0,1],"r":[[0,0,2,90]]}"""

        `when`("파싱하면") {
            then("이름 번역과 설명은 비어 있다") {
                val result = parser.parse(content, foods, candidates)
                result.nameTranslations shouldBe emptyMap()
                result.descriptions shouldBe emptyMap()
            }
        }
    }

    given("t 에 9개 번역이 고정 언어 순서로 담긴 응답") {
        val content =
            """{"c":[0],"r":[],"t":[[0,["炒饭","Fried rice","チャーハン","炒飯","Cơm chiên","Nasi goreng","ข้าวผัด","Жареный рис","Arroz frito"]]]}"""

        `when`("파싱하면") {
            then("위치 인덱스를 고정 언어 순서(KO 제외)로 복원해 이름 번역에 담는다") {
                val result = parser.parse(content, foods, candidates)
                val translations = result.nameTranslations[1L]
                translations.shouldNotBeNull()
                translations.keys shouldContainExactlyInAnyOrder listOf(
                    LanguageCode.ZH_HANS,
                    LanguageCode.EN,
                    LanguageCode.JA,
                    LanguageCode.ZH_HANT,
                    LanguageCode.VI,
                    LanguageCode.ID,
                    LanguageCode.TH,
                    LanguageCode.RU,
                    LanguageCode.ES,
                )
                translations[LanguageCode.ZH_HANS] shouldBe "炒饭"
                translations[LanguageCode.EN] shouldBe "Fried rice"
                translations[LanguageCode.ES] shouldBe "Arroz frito"
            }

            then("ko 는 이름 번역에 담기지 않는다") {
                val result = parser.parse(content, foods, candidates)
                result.nameTranslations[1L]!! shouldNotContainKey LanguageCode.KO
            }
        }
    }

    given("t 배열 길이가 9 미만이고 일부 원소가 blank 인 응답") {
        val content = """{"c":[0],"r":[],"t":[[0,["炒饭","","チャーハン"]]]}"""

        `when`("파싱하면") {
            then("존재하는 위치만 채택하고 blank·부족 위치는 언어 누락으로 둔다") {
                val result = parser.parse(content, foods, candidates)
                val translations = result.nameTranslations[1L]
                translations.shouldNotBeNull()
                translations.keys shouldContainExactlyInAnyOrder listOf(LanguageCode.ZH_HANS, LanguageCode.JA)
                translations[LanguageCode.ZH_HANS] shouldBe "炒饭"
                translations shouldNotContainKey LanguageCode.EN
            }
        }
    }

    given("t 에 같은 음식 인덱스가 중복된 응답") {
        val content =
            """{"c":[0],"r":[],"t":[[0,["첫炒饭","First"]],[0,["둘炒饭","Second"]]]}"""

        `when`("파싱하면") {
            then("음식당 첫 t 항목만 채택한다") {
                val result = parser.parse(content, foods, candidates)
                result.nameTranslations[1L]!![LanguageCode.EN] shouldBe "First"
            }
        }
    }

    given("t 에 범위를 벗어난 음식 인덱스 항목이 섞인 응답") {
        val content =
            """{"c":[0],"r":[],"t":[[9,["炒饭","Fried rice"]],[0,["김炒饭","Gimbap"]]]}"""

        `when`("파싱하면") {
            then("범위 이탈 t 항목은 스킵하고 유효 항목만 담는다") {
                val result = parser.parse(content, foods, candidates)
                result.nameTranslations.keys shouldContainExactlyInAnyOrder listOf(1L)
                result.nameTranslations[1L]!![LanguageCode.EN] shouldBe "Gimbap"
            }
        }
    }

    given("루트가 유효 JSON 이지만 객체가 아닌 응답") {
        `when`("배열 루트를 파싱하면") {
            then("ScoringResponseParseException 을 던진다") {
                shouldThrow<ScoringResponseParseException> {
                    parser.parse("[1,2,3]", foods, candidates)
                }
            }
        }

        `when`("문자열 루트를 파싱하면") {
            then("ScoringResponseParseException 을 던진다") {
                shouldThrow<ScoringResponseParseException> {
                    parser.parse("\"oops\"", foods, candidates)
                }
            }
        }
    }

    given("r 항목의 음식 인덱스가 음수인 응답") {
        val content = """{"c":[0],"r":[[-1,0,2,90],[0,1,1,40]]}"""

        `when`("파싱하면") {
            then("음수 인덱스 항목은 스킵하고 유효 항목은 유지한다") {
                val result = parser.parse(content, foods, candidates)
                result.included[1L]!!.map { it.code } shouldContainExactlyInAnyOrder listOf("MILK")
            }
        }
    }

    given("KB-53 과 동일한 판단 집합을 압축 배열로 표현한 응답") {
        val content = """{"c":[0,1],"r":[[0,0,2,90],[1,2,2,85]]}"""

        val expected = ModelScoring(
            included = mapOf(
                1L to listOf(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
                2L to listOf(SubstanceJudgement(code = "WHEAT", score = 2, probability = 85)),
            ),
            coveredFoodIds = setOf(1L, 2L),
        )

        `when`("압축 파서로 파싱하면") {
            then("KB-53 이 산출했을 included 와 동일하다") {
                val result = parser.parse(content, foods, candidates)
                result.included shouldBe expected.included
            }

            then("KB-53 이 산출했을 coveredFoodIds 와 동일하다") {
                val result = parser.parse(content, foods, candidates)
                result.coveredFoodIds shouldBe expected.coveredFoodIds
            }
        }
    }

    given("r 키가 없는 유효 JSON 응답") {
        val content = """{"c":[0,1]}"""

        `when`("파싱하면") {
            then("ScoringResponseParseException 을 던진다") {
                shouldThrow<ScoringResponseParseException> {
                    parser.parse(content, foods, candidates)
                }
            }
        }
    }

    given("r 이 배열이 아닌 응답") {
        val content = """{"c":[0,1],"r":"oops"}"""

        `when`("파싱하면") {
            then("ScoringResponseParseException 을 던진다") {
                shouldThrow<ScoringResponseParseException> {
                    parser.parse(content, foods, candidates)
                }
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
                    parser.parse("""{"r":[""", foods, candidates)
                }
            }
        }
    }
})
