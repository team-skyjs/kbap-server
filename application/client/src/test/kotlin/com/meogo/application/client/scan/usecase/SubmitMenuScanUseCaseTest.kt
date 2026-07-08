package com.meogo.application.client.scan.usecase

import com.meogo.application.client.scan.dto.BoundingBoxInput
import com.meogo.application.client.scan.dto.MenuScanItemInput
import com.meogo.application.client.scan.dto.SubmitMenuScanInput
import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.core.scan.MenuItemMatch
import com.meogo.core.scan.MenuScan
import com.meogo.core.scan.MenuScanRepository
import com.meogo.core.scan.PendingMenuRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class CapturingScanRepository : MenuScanRepository {
    var saved: MenuScan? = null
        private set

    override fun save(menuScan: MenuScan): MenuScan {
        saved = menuScan
        return MenuScan.reconstitute(
            id = 1L,
            status = menuScan.status,
            items = menuScan.items.mapIndexed { i, item -> item.copy(id = (i + 1).toLong()) },
        )
    }

    override fun findById(scanId: Long): MenuScan? = null
}

private class FakeFoodRepository(private val keyToId: Map<String, Long>) : FoodRepository {
    override fun findById(id: Long): Food? = null
    override fun findMenuPage(cursor: Long?, size: Int): List<Food> = emptyList()
    override fun findFoodIdByKoreanMatchKey(key: String): Long? = keyToId[key]
}

private class RecordingPendingRepository : PendingMenuRepository {
    val enqueued = mutableListOf<String>()
    override fun enqueue(name: String) {
        enqueued += name
    }
}

private class FakeInterpreter(private val results: List<InterpretedName>) : ScannedNameInterpreter {
    var callCount = 0
        private set

    override fun interpret(texts: List<String>): List<InterpretedName> {
        callCount++
        return results
    }
}

private class ThrowingInterpreter : ScannedNameInterpreter {
    override fun interpret(texts: List<String>): List<InterpretedName> = throw RuntimeException("LLM 장애")
}

class SubmitMenuScanUseCaseTest : BehaviorSpec({
    fun item(itemId: Int, name: String) = MenuScanItemInput(
        itemId = itemId,
        rawMenuName = name,
        boundingBox = BoundingBoxInput(0.1, 0.1, 0.3, 0.1),
    )

    fun useCase(
        food: Map<String, Long> = emptyMap(),
        interpreter: ScannedNameInterpreter? = null,
        pending: RecordingPendingRepository = RecordingPendingRepository(),
        scanRepo: CapturingScanRepository = CapturingScanRepository(),
    ) = SubmitMenuScanUseCase(
        menuScanRepository = scanRepo,
        foodRepository = FakeFoodRepository(food),
        pendingMenuRepository = pending,
        riskAssessor = MockCyclingRiskAssessor(),
        interpreter = interpreter,
    )

    given("정제 서비스가 정상일 때") {
        `when`("표준명이 저장 음식과 일치하면") {
            then("MATCHED(foodId) 로 매칭한다") {
                val scanRepo = CapturingScanRepository()
                val uc = useCase(
                    food = mapOf("김치찌개" to 7L),
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("김치찌개"))),
                    scanRepo = scanRepo,
                )

                uc.submit(SubmitMenuScanInput(listOf(item(0, "김치찌개 kimchi jjigae"))))

                scanRepo.saved!!.items.first().match shouldBe MenuItemMatch.Matched(7L)
            }
        }

        `when`("표준명이 저장에 없으면") {
            then("PENDING 이고 표준명을 대기열에 등록한다") {
                val pending = RecordingPendingRepository()
                val scanRepo = CapturingScanRepository()
                val uc = useCase(
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("우주라면"))),
                    pending = pending,
                    scanRepo = scanRepo,
                )

                uc.submit(SubmitMenuScanInput(listOf(item(0, "우주라면"))))

                scanRepo.saved!!.items.first().match shouldBe MenuItemMatch.Pending
                pending.enqueued shouldBe listOf("우주라면")
            }
        }

        `when`("LLM 이 NOT_FOOD 로 판정하면") {
            then("NOT_FOOD 이고 대기열에 등록하지 않는다") {
                val pending = RecordingPendingRepository()
                val scanRepo = CapturingScanRepository()
                val uc = useCase(
                    interpreter = FakeInterpreter(listOf(InterpretedName.NotFood)),
                    pending = pending,
                    scanRepo = scanRepo,
                )

                uc.submit(SubmitMenuScanInput(listOf(item(0, "원산지 중국"))))

                scanRepo.saved!!.items.first().match shouldBe MenuItemMatch.NotFood
                pending.enqueued shouldBe emptyList()
            }
        }

        `when`("한글이 전혀 없는 항목이면") {
            then("LLM 을 거치지 않고 NOT_FOOD 로 처리한다") {
                val interpreter = FakeInterpreter(emptyList())
                val scanRepo = CapturingScanRepository()
                val uc = useCase(interpreter = interpreter, scanRepo = scanRepo)

                uc.submit(SubmitMenuScanInput(listOf(item(0, "MacBook Air F9"))))

                scanRepo.saved!!.items.first().match shouldBe MenuItemMatch.NotFood
                interpreter.callCount shouldBe 0
            }
        }

        `when`("여러 항목을 제출하면") {
            then("스캔당 LLM 을 1번만 호출한다") {
                val interpreter = FakeInterpreter(
                    listOf(InterpretedName.StandardName("김치찌개"), InterpretedName.NotFood),
                )
                val uc = useCase(food = mapOf("김치찌개" to 7L), interpreter = interpreter)

                uc.submit(SubmitMenuScanInput(listOf(item(0, "김치찌개"), item(1, "원산지 중국"))))

                interpreter.callCount shouldBe 1
            }
        }

        `when`("한 스캔 안에서 같은 표준명이 여러 항목으로 나오면") {
            then("대기열에는 그 표준명을 1번만 등록한다") {
                val pending = RecordingPendingRepository()
                val uc = useCase(
                    interpreter = FakeInterpreter(
                        listOf(InterpretedName.StandardName("우주라면"), InterpretedName.StandardName("우주라면")),
                    ),
                    pending = pending,
                )

                uc.submit(SubmitMenuScanInput(listOf(item(0, "우주라면"), item(1, "우주 라면"))))

                pending.enqueued shouldBe listOf("우주라면")
            }
        }
    }

    given("정제 서비스가 없거나 장애일 때(폴백)") {
        `when`("interpreter 가 주입되지 않았고 정규화 키가 저장 음식과 일치하면") {
            then("정규화 exact 매치로 MATCHED 한다") {
                val scanRepo = CapturingScanRepository()
                val uc = useCase(food = mapOf("김치찌개" to 7L), interpreter = null, scanRepo = scanRepo)

                uc.submit(SubmitMenuScanInput(listOf(item(0, "김치찌개"))))

                scanRepo.saved!!.items.first().match shouldBe MenuItemMatch.Matched(7L)
            }
        }

        `when`("interpreter 가 요청보다 적은 개수를 반환하면") {
            then("결과를 신뢰하지 않고 정규화 exact 매치 폴백으로 처리한다") {
                val scanRepo = CapturingScanRepository()
                val uc = useCase(
                    food = mapOf("김치찌개" to 7L),
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("무시됨"))),
                    scanRepo = scanRepo,
                )

                uc.submit(SubmitMenuScanInput(listOf(item(0, "김치찌개"), item(1, "우주라면"))))

                val items = scanRepo.saved!!.items
                items.first { it.itemId == 0 }.match shouldBe MenuItemMatch.Matched(7L)
                items.first { it.itemId == 1 }.match shouldBe MenuItemMatch.Pending
            }
        }

        `when`("interpreter 가 예외를 던지면") {
            then("아는 메뉴는 MATCHED, 나머지는 원문으로 PENDING+대기열 강등한다") {
                val pending = RecordingPendingRepository()
                val scanRepo = CapturingScanRepository()
                val uc = useCase(
                    food = mapOf("김치찌개" to 7L),
                    interpreter = ThrowingInterpreter(),
                    pending = pending,
                    scanRepo = scanRepo,
                )

                uc.submit(SubmitMenuScanInput(listOf(item(0, "김치찌개"), item(1, "우주라면 space"))))

                val items = scanRepo.saved!!.items
                items.first { it.itemId == 0 }.match shouldBe MenuItemMatch.Matched(7L)
                items.first { it.itemId == 1 }.match shouldBe MenuItemMatch.Pending
                pending.enqueued shouldBe listOf("우주라면 space")
            }
        }
    }
})
