package com.kbap.batch.notification

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

private fun seoul(hour: Int, minute: Int, second: Int): Clock =
    Clock.fixed(ZonedDateTime.of(2026, 9, 15, hour, minute, second, 0, SEOUL).toInstant(), ZoneId.of("UTC"))

class ScanSuggestionSendWindowTest : BehaviorSpec({
    given("발송 허용 시간대 08:00~21:00 KST") {
        `when`("07:59:59 이면") {
            then("닫혀 있다") { ScanSuggestionSendWindow.isOpen(seoul(7, 59, 59)) shouldBe false }
        }
        `when`("08:00:00 이면") {
            then("열려 있다") { ScanSuggestionSendWindow.isOpen(seoul(8, 0, 0)) shouldBe true }
        }
        `when`("20:59:59 이면") {
            then("열려 있다") { ScanSuggestionSendWindow.isOpen(seoul(20, 59, 59)) shouldBe true }
        }
        `when`("21:00:00 이면") {
            then("닫혀 있다") { ScanSuggestionSendWindow.isOpen(seoul(21, 0, 0)) shouldBe false }
        }
    }

    given("오늘(KST) 시작 시각") {
        val expected = LocalDateTime.of(2026, 9, 15, 0, 0).atZone(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

        `when`("정오 KST 시계로 계산하면") {
            then("KST 자정을 JVM 시간대의 LocalDateTime 으로 돌려준다") {
                ScanSuggestionSendWindow.startOfToday(seoul(12, 0, 0)) shouldBe expected
            }
        }
        `when`("00:30 KST 시계로 계산하면") {
            then("같은 날 자정이다") {
                ScanSuggestionSendWindow.startOfToday(seoul(0, 30, 0)) shouldBe expected
            }
        }
    }
})
