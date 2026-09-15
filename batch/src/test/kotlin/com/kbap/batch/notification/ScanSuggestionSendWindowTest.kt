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

    given("현재 발송 슬롯 시작 시각 (12:00·18:00 KST)") {
        fun jvm(day: Int, hour: Int): LocalDateTime =
            LocalDateTime.of(2026, 9, day, hour, 0).atZone(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

        `when`("11:59 KST 이면") {
            then("전날 18:00 이다") { ScanSuggestionSendWindow.startOfCurrentSlot(seoul(11, 59, 0)) shouldBe jvm(14, 18) }
        }
        `when`("12:00 KST 이면") {
            then("오늘 12:00 이다") { ScanSuggestionSendWindow.startOfCurrentSlot(seoul(12, 0, 0)) shouldBe jvm(15, 12) }
        }
        `when`("17:59 KST 이면") {
            then("오늘 12:00 이다") { ScanSuggestionSendWindow.startOfCurrentSlot(seoul(17, 59, 0)) shouldBe jvm(15, 12) }
        }
        `when`("18:00 KST 이면") {
            then("오늘 18:00 이다") { ScanSuggestionSendWindow.startOfCurrentSlot(seoul(18, 0, 0)) shouldBe jvm(15, 18) }
        }
        `when`("23:00 KST 이면") {
            then("오늘 18:00 이고 JVM 시간대로 변환된 LocalDateTime 이다") {
                ScanSuggestionSendWindow.startOfCurrentSlot(seoul(23, 0, 0)) shouldBe jvm(15, 18)
            }
        }
    }
})
