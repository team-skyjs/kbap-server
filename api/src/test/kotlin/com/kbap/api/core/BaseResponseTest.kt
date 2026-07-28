package com.kbap.api.core

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
            then("success=false·code·message 를 담고 payload 는 기본 null 이다") {
                val response = BaseResponse.fail("AUTH-004", "something wrong")

                response.success shouldBe false
                response.code shouldBe "AUTH-004"
                response.payload.shouldBeNull()
                response.message shouldBe "something wrong"
            }
        }

        `when`("fail 에 후속 동작용 payload 를 담으면") {
            then("payload 가 그대로 실린다") {
                val response = BaseResponse.fail("FOOD-003", "bad keyword", mapOf("sanitizedKeyword" to "김치"))

                response.success shouldBe false
                response.payload shouldBe mapOf("sanitizedKeyword" to "김치")
            }
        }
    }
})
