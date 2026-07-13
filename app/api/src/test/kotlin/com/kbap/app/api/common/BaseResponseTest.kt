package com.kbap.app.api.common

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class BaseResponseTest : BehaviorSpec({
    given("BaseResponse 생성") {
        `when`("ok 로 생성하면") {
            then("success=true·payload·message=null 이다") {
                val response = BaseResponse.ok("payload")

                response.success shouldBe true
                response.payload shouldBe "payload"
                response.message.shouldBeNull()
            }
        }

        `when`("fail 로 생성하면") {
            then("success=false·payload=null·message 사유이다") {
                val response = BaseResponse.fail("something wrong")

                response.success shouldBe false
                response.payload.shouldBeNull()
                response.message shouldBe "something wrong"
            }
        }
    }
})
