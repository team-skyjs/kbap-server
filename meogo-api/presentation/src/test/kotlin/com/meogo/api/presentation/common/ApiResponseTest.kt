package com.meogo.api.presentation.common

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ApiResponseTest : BehaviorSpec({
    given("ApiResponse 생성") {
        `when`("ok 로 생성하면") {
            then("success=true·data 페이로드·message=null 이다") {
                val response = ApiResponse.ok("payload")

                response.success shouldBe true
                response.data shouldBe "payload"
                response.message.shouldBeNull()
            }
        }

        `when`("fail 로 생성하면") {
            then("success=false·data=null·message 사유이다") {
                val response = ApiResponse.fail("something wrong")

                response.success shouldBe false
                response.data.shouldBeNull()
                response.message shouldBe "something wrong"
            }
        }
    }
})
