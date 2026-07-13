package com.kbap.app.api.architecture

import com.kbap.core.error.ErrorCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus

class ErrorCodeStatusTest : BehaviorSpec({

    given("통합 ErrorCode enum status") {
        `when`("전체 항목을 조회하면") {
            then("최소 하나 이상 존재한다") {
                ErrorCode.entries.shouldNotBeEmpty()
            }
        }

        `when`("각 status 정수를 HTTP 상태로 변환하면") {
            then("실제 존재하는 4xx 또는 5xx 코드로 매핑된다") {
                ErrorCode.entries.forEach { errorCode ->
                    withClue("$errorCode -> status=${errorCode.status}") {
                        val status = HttpStatus.resolve(errorCode.status)
                        status.shouldNotBeNull()
                        (status.is4xxClientError || status.is5xxServerError) shouldBe true
                    }
                }
            }
        }
    }
})
