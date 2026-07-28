package com.kbap.api.common

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CursorParserTest : BehaviorSpec({
    given("CursorResolver 커서 해석") {
        `when`("커서가 null 이면") {
            then("null 을 반환한다") {
                CursorParser.parse(null) shouldBe null
            }
        }

        `when`("커서가 빈 문자열이면") {
            then("첫 페이지로 보고 null 을 반환한다") {
                CursorParser.parse("") shouldBe null
            }
        }

        `when`("커서가 공백만 있으면") {
            then("첫 페이지로 보고 null 을 반환한다") {
                CursorParser.parse("   ") shouldBe null
            }
        }

        `when`("커서가 유효한 양수 문자열이면") {
            then("해당 Long 값을 반환한다") {
                CursorParser.parse("100") shouldBe 100L
            }
        }

        `when`("커서가 0 이면") {
            then("경계값 0L 을 그대로 반환한다") {
                CursorParser.parse("0") shouldBe 0L
            }
        }

        `when`("커서가 숫자가 아니면") {
            then("INVALID_CURSOR BusinessException 을 던진다") {
                shouldThrow<BusinessException> {
                    CursorParser.parse("abc")
                }.errorCode shouldBe ErrorCode.INVALID_CURSOR
            }
        }

        `when`("커서가 음수이면") {
            then("INVALID_CURSOR BusinessException 을 던진다") {
                shouldThrow<BusinessException> {
                    CursorParser.parse("-1")
                }.errorCode shouldBe ErrorCode.INVALID_CURSOR
            }
        }

        `when`("커서가 공백을 포함한 숫자이면") {
            then("파싱에 실패해 INVALID_CURSOR BusinessException 을 던진다") {
                shouldThrow<BusinessException> {
                    CursorParser.parse(" 100 ")
                }.errorCode shouldBe ErrorCode.INVALID_CURSOR
            }
        }
    }
})
