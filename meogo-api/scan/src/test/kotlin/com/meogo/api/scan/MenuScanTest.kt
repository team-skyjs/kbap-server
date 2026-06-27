package com.meogo.api.scan

import com.meogo.api.core.risk.RiskLevel
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MenuScanTest : BehaviorSpec({
    fun item(itemId: Int) = ScannedMenuItem(
        itemId = itemId,
        rawMenuName = "메뉴$itemId",
        boundingBox = BoundingBox(0.0, 0.0, 0.5, 0.5),
        receivedOrder = itemId,
        assessment = MenuItemAssessment(RiskLevel.SAFE, "r"),
    )

    given("MenuScan 생성") {
        `when`("항목이 비어 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MenuScan.create(emptyList()) }
            }
        }

        `when`("항목이 100개를 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MenuScan.create((0..100).map { item(it) }) }
            }
        }

        `when`("itemId 가 스캔 내에서 중복이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MenuScan.create(listOf(item(1), item(1))) }
            }
        }

        `when`("유효한 항목 1..100 개가 주어지면") {
            then("예외 없이 생성된다") {
                shouldNotThrowAny { MenuScan.create((1..100).map { item(it) }) }
            }

            then("상태가 COMPLETED 다") {
                MenuScan.create(listOf(item(0))).status shouldBe ScanStatus.COMPLETED
            }
        }
    }
})
