package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstanceCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MockAvoidedSubstanceProviderTest : BehaviorSpec({
    given("Mock 사용자 회피 성분 제공자") {
        `when`("회피 성분 목록을 조회하면") {
            then("고정된 성분 집합(SOY·MILK·PEANUT·SHRIMP·EGG)을 반환한다") {
                MockAvoidedSubstanceProvider().avoidedCodes() shouldBe setOf(
                    AvoidanceSubstanceCode.SOY,
                    AvoidanceSubstanceCode.MILK,
                    AvoidanceSubstanceCode.PEANUT,
                    AvoidanceSubstanceCode.SHRIMP,
                    AvoidanceSubstanceCode.EGG,
                )
            }
        }
    }
})
