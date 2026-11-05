package com.kbap.api.infra.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class TelegramOperatorAlertSenderTest : BehaviorSpec({
    given("텔레그램 운영진 알림 — 보안 검토") {
        `when`("호출이 실패하면") {
            then("봇 토큰이 담긴 URI·원인 예외 없이 정리된 예외를 던진다(로그에 토큰이 남지 않게)") {
                val sender = TelegramOperatorAlertSender(botToken = "SECRET-TOKEN-123", chatId = "1", baseUrl = "http://127.0.0.1:1")

                val error = shouldThrow<IllegalStateException> { sender.send("hello") }

                error.message!! shouldNotContain "SECRET-TOKEN-123"
                error.cause shouldBe null
                error.stackTraceToString() shouldNotContain "SECRET-TOKEN-123"
            }
        }
    }
})
