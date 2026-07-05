package com.meogo.infra.persistence.scan
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.scan.BoundingBox
import com.meogo.core.scan.MenuItemAssessment
import com.meogo.core.scan.MenuScan
import com.meogo.core.scan.ScanStatus
import com.meogo.core.scan.ScannedMenuItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MenuScanRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: MenuScanRepositoryAdapter

    @Autowired
    private lateinit var jpaRepository: MenuScanJpaRepository

    init {
        fun menuScan(items: List<ScannedMenuItem>) =
            MenuScan.create(MenuScan.CreationSpec(items))

        given("MenuScan 저장소 어댑터") {
            `when`("항목·boundingBox·판정을 가진 스캔을 저장하면") {
                then("scanId 가 부여되고 조회 시 모두 보존된다") {
                    val scan = menuScan(
                        listOf(
                            ScannedMenuItem(
                                itemId = 0,
                                rawMenuName = "된장찌개",
                                boundingBox = BoundingBox(0.12, 0.34, 0.5, 0.08),
                                assessment = MenuItemAssessment(RiskLevel.SAFE, "mock: 안전"),
                            ),
                            ScannedMenuItem(
                                itemId = 1,
                                rawMenuName = "김치찌개",
                                boundingBox = BoundingBox(0.0, 0.0, 0.5, 0.5),
                                assessment = MenuItemAssessment(RiskLevel.CAUTION, "mock: 주의"),
                            ),
                        ),
                    )

                    val saved = adapter.save(scan)
                    val savedId = saved.id.shouldNotBeNull()

                    val loaded = adapter.findById(savedId)
                    loaded.shouldNotBeNull()
                    loaded.status shouldBe ScanStatus.COMPLETED
                    loaded.items.size shouldBe 2

                    val first = loaded.items.first { it.itemId == 0 }
                    first.rawMenuName shouldBe "된장찌개"
                    first.boundingBox shouldBe BoundingBox(0.12, 0.34, 0.5, 0.08)
                    first.assessment shouldBe MenuItemAssessment(RiskLevel.SAFE, "mock: 안전")

                    val second = loaded.items.first { it.itemId == 1 }
                    second.assessment.riskLevel shouldBe RiskLevel.CAUTION
                }
            }

            `when`("존재하지 않는 scanId 로 조회하면") {
                then("null 을 반환한다") {
                    adapter.findById(999_999L) shouldBe null
                }
            }

            `when`("저장된 스캔을 소프트 삭제하면") {
                then("@SQLRestriction 으로 조회에서 제외돼 null 이 반환된다") {
                    val scan = menuScan(
                        listOf(
                            ScannedMenuItem(
                                itemId = 0,
                                rawMenuName = "제육볶음",
                                boundingBox = BoundingBox(0.1, 0.1, 0.2, 0.2),
                                assessment = MenuItemAssessment(RiskLevel.SAFE, "mock: 안전"),
                            ),
                        ),
                    )
                    val savedId = adapter.save(scan).id.shouldNotBeNull()

                    val entity = jpaRepository.findById(savedId).get()
                    entity.delete()
                    jpaRepository.save(entity)

                    adapter.findById(savedId) shouldBe null
                }
            }
        }
    }
}
