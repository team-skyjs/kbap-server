package com.kbap.common.core.menu

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class KoreanMenuNameNormalizerTest : BehaviorSpec({
    given("KoreanMenuNameNormalizer.matchKey") {
        `when`("한글에 로마자 음역이 섞이면") {
            then("한글만 남긴다") {
                KoreanMenuNameNormalizer.matchKey("김치찌개 kimchi jjigae") shouldBe "김치찌개"
            }
        }

        `when`("선두 기호·전후 공백이 붙으면") {
            then("모두 제거한다") {
                KoreanMenuNameNormalizer.matchKey("· 된장찌개 ") shouldBe "된장찌개"
            }
        }

        `when`("한글 사이에 공백이 있으면") {
            then("공백을 제거해 같은 키가 된다") {
                KoreanMenuNameNormalizer.matchKey("돼지 국밥") shouldBe "돼지국밥"
                KoreanMenuNameNormalizer.matchKey("돼지국밥") shouldBe "돼지국밥"
            }
        }

        `when`("원산지 문구처럼 한글과 기호가 섞이면") {
            then("한글만 이어 붙인다") {
                KoreanMenuNameNormalizer.matchKey("원산지: 중국") shouldBe "원산지중국"
            }
        }

        `when`("한글이 전혀 없으면(숫자·라틴만)") {
            then("빈 문자열을 반환한다") {
                KoreanMenuNameNormalizer.matchKey("6,500") shouldBe ""
                KoreanMenuNameNormalizer.matchKey("MacBook Air F9") shouldBe ""
            }
        }
    }
})
