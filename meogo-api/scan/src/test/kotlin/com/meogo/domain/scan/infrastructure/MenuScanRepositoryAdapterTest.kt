package com.meogo.domain.scan.infrastructure

import com.meogo.core.risk.RiskLevel
import com.meogo.domain.scan.BoundingBox
import com.meogo.domain.scan.MenuItemAssessment
import com.meogo.domain.scan.MenuScan
import com.meogo.domain.scan.ScanStatus
import com.meogo.domain.scan.ScannedMenuItem
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * H2 로 저장/조회를 검증한다(SC-006): scanId·항목·boundingBox·판정 스냅샷이 보존되는지.
 * (Boot 4.x 는 @DataJpaTest 슬라이스가 별도 모듈이라, scan 단독 테스트는 전체 컨텍스트 + 롤백으로 대체.)
 */
@SpringBootTest
@Transactional
class MenuScanRepositoryAdapterTest {

    @Autowired
    private lateinit var adapter: MenuScanRepositoryAdapter

    @Test
    fun `저장하면 scanId 가 부여되고 항목·boundingBox·판정이 보존된다`() {
        val scan = MenuScan.create(
            listOf(
                ScannedMenuItem(
                    itemId = 0,
                    rawMenuName = "된장찌개",
                    boundingBox = BoundingBox(0.12, 0.34, 0.5, 0.08),
                    receivedOrder = 0,
                    assessment = MenuItemAssessment(RiskLevel.SAFE, "mock: 안전"),
                ),
                ScannedMenuItem(
                    itemId = 1,
                    rawMenuName = "김치찌개",
                    boundingBox = BoundingBox(0.0, 0.0, 0.5, 0.5),
                    receivedOrder = 1,
                    assessment = MenuItemAssessment(RiskLevel.CAUTION, "mock: 주의"),
                ),
            ),
        )

        val saved = adapter.save(scan)
        saved.id.shouldNotBeNull()

        val loaded = adapter.findById(saved.id!!)
        loaded.shouldNotBeNull()
        loaded.status shouldBe ScanStatus.COMPLETED
        loaded.items.size shouldBe 2

        val first = loaded.items.first { it.itemId == 0 }
        first.rawMenuName shouldBe "된장찌개"
        first.boundingBox shouldBe BoundingBox(0.12, 0.34, 0.5, 0.08)
        first.receivedOrder shouldBe 0
        first.assessment shouldBe MenuItemAssessment(RiskLevel.SAFE, "mock: 안전")

        val second = loaded.items.first { it.itemId == 1 }
        second.assessment.riskLevel shouldBe RiskLevel.CAUTION
    }

    @Test
    fun `없는 scanId 조회는 null`() {
        adapter.findById(999_999L) shouldBe null
    }
}
