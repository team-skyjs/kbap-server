package com.meogo.api.presentation.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ApiResponseTest : StringSpec({
    "ok 는 success=true·data 페이로드·message=null 이다" {
        val response = ApiResponse.ok("payload")

        response.success shouldBe true
        response.data shouldBe "payload"
        response.message.shouldBeNull()
    }

    "fail 은 success=false·data=null·message 사유이다" {
        val response = ApiResponse.fail("something wrong")

        response.success shouldBe false
        response.data.shouldBeNull()
        response.message shouldBe "something wrong"
    }
})
