package com.kbap.common.domain.notification.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class NotificationTest : BehaviorSpec({
    fun reminder(data: Map<String, Any>?) =
        Notification.forMember(1L, NotificationType.REVIEW_REMINDER, "t", "b", data)

    given("알림의 foodId 추출") {
        `when`("REVIEW_REMINDER 의 data 에 foodId 가 정수로 있으면") {
            then("그 값을 돌려준다") {
                reminder(mapOf("foodId" to 7)).foodIdOrNull() shouldBe 7L
                reminder(mapOf("foodId" to 7L)).foodIdOrNull() shouldBe 7L
            }
        }

        `when`("REVIEW_REMINDER 의 data 에 foodId 가 정수 문자열로 있으면") {
            then("정수로 해석해 돌려준다") {
                reminder(mapOf("foodId" to "7")).foodIdOrNull() shouldBe 7L
            }
        }

        `when`("REVIEW_REMINDER 의 foodId 가 정수로 해석되지 않으면") {
            then("null 이다") {
                reminder(mapOf("foodId" to "abc")).foodIdOrNull() shouldBe null
                reminder(mapOf("foodId" to "")).foodIdOrNull() shouldBe null
                reminder(mapOf("foodId" to 7.5)).foodIdOrNull() shouldBe null
                reminder(mapOf("foodId" to true)).foodIdOrNull() shouldBe null
            }
        }

        `when`("REVIEW_REMINDER 인데 data 가 없거나 foodId 키가 없으면") {
            then("null 이다") {
                reminder(null).foodIdOrNull() shouldBe null
                reminder(mapOf("type" to "REVIEW_REMINDER")).foodIdOrNull() shouldBe null
            }
        }

        `when`("REVIEW_REMINDER 가 아닌 유형의 data 에 foodId 가 있으면") {
            then("유형이 기준이라 null 이다") {
                NotificationType.entries.filter { it != NotificationType.REVIEW_REMINDER }.forEach { type ->
                    Notification.forMember(1L, type, "t", "b", mapOf("foodId" to 7)).foodIdOrNull() shouldBe null
                }
            }
        }
    }
})
