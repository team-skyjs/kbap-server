package com.meogo.domain.scan

import com.meogo.core.risk.RiskLevel
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MenuScanTest : StringSpec({
    fun item(itemId: Int) = ScannedMenuItem(
        itemId = itemId,
        rawMenuName = "메뉴$itemId",
        boundingBox = BoundingBox(0.0, 0.0, 0.5, 0.5),
        receivedOrder = itemId,
        assessment = MenuItemAssessment(RiskLevel.SAFE, "r"),
    )

    "항목이 비어 있으면 예외" {
        shouldThrow<IllegalArgumentException> { MenuScan.create(emptyList()) }
    }

    "항목이 100개를 초과하면 예외" {
        val items = (0..100).map { item(it) }
        shouldThrow<IllegalArgumentException> { MenuScan.create(items) }
    }

    "itemId 가 스캔 내에서 중복이면 예외" {
        shouldThrow<IllegalArgumentException> { MenuScan.create(listOf(item(1), item(1))) }
    }

    "정상 항목 1..100 은 COMPLETED 로 생성된다" {
        shouldNotThrowAny {
            MenuScan.create((1..100).map { item(it) })
        }
        MenuScan.create(listOf(item(0))).status shouldBe ScanStatus.COMPLETED
    }
})
