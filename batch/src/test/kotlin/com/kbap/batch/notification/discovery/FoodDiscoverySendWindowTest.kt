package com.kbap.batch.notification.discovery

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

private fun seoul(hour: Int, minute: Int): Clock =
    Clock.fixed(ZonedDateTime.of(2026, 11, 4, hour, minute, 0, 0, SEOUL).toInstant(), ZoneId.of("UTC"))

private fun jvm(day: Int, hour: Int): LocalDateTime =
    LocalDateTime.of(2026, 11, day, hour, 0).atZone(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

class FoodDiscoverySendWindowTest : BehaviorSpec({
    given("현재 슬롯 시작 시각 (09·12·15·18시 KST)") {
        `when`("08:59 이면") {
            then("전날 18시, 태그 18") {
                FoodDiscoverySendWindow.startOfCurrentSlot(seoul(8, 59)) shouldBe jvm(3, 18)
                FoodDiscoverySendWindow.slotTag(seoul(8, 59)) shouldBe "18"
            }
        }
        `when`("09:00 이면") {
            then("오늘 09시, 태그 09") {
                FoodDiscoverySendWindow.startOfCurrentSlot(seoul(9, 0)) shouldBe jvm(4, 9)
                FoodDiscoverySendWindow.slotTag(seoul(9, 0)) shouldBe "09"
            }
        }
        `when`("14:59 이면") {
            then("오늘 12시") { FoodDiscoverySendWindow.startOfCurrentSlot(seoul(14, 59)) shouldBe jvm(4, 12) }
        }
        `when`("20:59 이면") {
            then("오늘 18시") { FoodDiscoverySendWindow.startOfCurrentSlot(seoul(20, 59)) shouldBe jvm(4, 18) }
        }
    }

    given("발송 허용 시간대 (09:00 이상 21:00 미만 KST)") {
        `when`("경계 시각을 보면") {
            then("08:59 닫힘, 09:00 열림, 20:59 열림, 21:00 닫힘") {
                FoodDiscoverySendWindow.isOpen(seoul(8, 59)) shouldBe false
                FoodDiscoverySendWindow.isOpen(seoul(9, 0)) shouldBe true
                FoodDiscoverySendWindow.isOpen(seoul(20, 59)) shouldBe true
                FoodDiscoverySendWindow.isOpen(seoul(21, 0)) shouldBe false
            }
        }
    }
})
