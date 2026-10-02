package com.kbap.common.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LanguageCodeSourceCodeTest : BehaviorSpec({

    fun verify(cases: Map<String?, String?>) = cases.forEach { (tag, expected) ->
        withClue("tag=$tag") { LanguageCode.sourceCodeOf(tag) shouldBe expected }
    }

    given("번역 엔진이 알려 준 원문 언어 태그") {
        `when`("앱이 아는 언어면") {
            then("앱이 lang 으로 쓰는 코드와 글자까지 같게 맞춘다 — 대소문자·지역 꼬리표를 정리한다") {
                verify(
                    mapOf(
                        "en" to "en",
                        "EN" to "en",
                        "en-US" to "en",
                        "ko-KR" to "ko",
                        "ja" to "ja",
                        "es-419" to "es",
                        " ru " to "ru",
                        "id" to "id",
                        "th-TH" to "th",
                        "vi" to "vi",
                    ),
                )
            }
        }

        `when`("앱이 아는 언어를 다른 코드로 부르면(세 글자 코드·옛 코드)") {
            then("앱 코드로 맞춘다 — eng 를 모르는 언어로 두면 같은 언어인데도 다르다고 판정한다") {
                verify(
                    mapOf(
                        "eng" to "en",
                        "ENG-us" to "en",
                        "kor" to "ko",
                        "jpn" to "ja",
                        "vie" to "vi",
                        "ind" to "id",
                        "in" to "id",
                        "tha" to "th",
                        "rus" to "ru",
                        "spa" to "es",
                        "zho" to "zh-Hans",
                        "chi" to "zh-Hans",
                        "cmn" to "zh-Hans",
                        "zho-Hant" to "zh-Hant",
                        "cmn-TW" to "zh-Hant",
                        "fra" to "fra",
                    ),
                )
            }
        }

        `when`("중국어면") {
            then("문자 기준으로 간체·번체를 가른다 — 문자 표기가 없으면 지역으로, 그것도 없으면 간체다") {
                verify(
                    mapOf(
                        "zh-Hans" to "zh-Hans",
                        "zh-hant" to "zh-Hant",
                        "zh-Hant-TW" to "zh-Hant",
                        "zh-TW" to "zh-Hant",
                        "zh-HK" to "zh-Hant",
                        "zh-MO" to "zh-Hant",
                        "zh-CN" to "zh-Hans",
                        "zh-SG" to "zh-Hans",
                        "zh" to "zh-Hans",
                        "ZH" to "zh-Hans",
                    ),
                )
            }
        }

        `when`("앱이 모르는 언어면") {
            then("언어 부분만 소문자로 준다") {
                verify(mapOf("fr" to "fr", "fr-CA" to "fr", "PT-br" to "pt", "de-DE" to "de", "fil" to "fil"))
            }
        }

        `when`("판별하지 못했거나 태그 모양이 아니면") {
            then("null 이다 — 긴 문자열이나 문장이 저장·응답으로 새지 않는다") {
                verify(
                    mapOf(
                        null to null,
                        "" to null,
                        "   " to null,
                        "und" to null,
                        "UND" to null,
                        "mul" to null,
                        "mis" to null,
                        "zxx" to null,
                        "mixed" to null,
                        "Japanese" to null,
                        "English" to null,
                        "en US" to null,
                        "english language" to null,
                        "e" to null,
                        "en-" to null,
                        "en_US" to null,
                        "x".repeat(40) to null,
                        "en-" + "a".repeat(40) to null,
                        "한국어" to null,
                    ),
                )
            }
        }

        `when`("어떤 입력이든") {
            then("결과는 저장 컬럼(35자)을 넘지 않는다") {
                listOf("zh-Hant-TW-x-private-use-long", "en-US-x-" + "a".repeat(8), "abc-" + "Defg-".repeat(5) + "xy").forEach { tag ->
                    withClue(tag) { ((LanguageCode.sourceCodeOf(tag)?.length ?: 0) <= 35) shouldBe true }
                }
            }
        }
    }
})
