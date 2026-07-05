package com.meogo.core.kernel.risk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RiskLevelTest : BehaviorSpec({
    given("포함 확률로 위험도를 판정한다") {
        `when`("확률이 CAUTION 임계값 미만이면") {
            then("확률 1 은 SAFE 다") {
                RiskLevel.fromInclusionProbability(1) shouldBe RiskLevel.SAFE
            }
            then("확률 9 는 SAFE 다") {
                RiskLevel.fromInclusionProbability(9) shouldBe RiskLevel.SAFE
            }
        }
        `when`("확률이 유효 범위(1..100) 밖이면") {
            then("확률 0 은 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { RiskLevel.fromInclusionProbability(0) }
            }
            then("확률 -1 은 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { RiskLevel.fromInclusionProbability(-1) }
            }
            then("확률 101 은 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { RiskLevel.fromInclusionProbability(101) }
            }
        }
        `when`("확률이 CAUTION 임계값 이상 DANGER 임계값 미만이면") {
            then("확률 10 은 CAUTION 이다") {
                RiskLevel.fromInclusionProbability(10) shouldBe RiskLevel.CAUTION
            }
            then("확률 59 는 CAUTION 이다") {
                RiskLevel.fromInclusionProbability(59) shouldBe RiskLevel.CAUTION
            }
        }
        `when`("확률이 DANGER 임계값 이상이면") {
            then("확률 60 은 DANGER 다") {
                RiskLevel.fromInclusionProbability(60) shouldBe RiskLevel.DANGER
            }
            then("확률 100 은 DANGER 다") {
                RiskLevel.fromInclusionProbability(100) shouldBe RiskLevel.DANGER
            }
        }
    }

    given("여러 위험도를 종합해 최종 위험도를 판정한다") {
        `when`("위험도 목록이 비어 있으면") {
            then("SAFE 다") {
                RiskLevel.aggregate(emptyList()) shouldBe RiskLevel.SAFE
            }
        }
        `when`("모두 SAFE 면") {
            then("SAFE 다") {
                RiskLevel.aggregate(listOf(RiskLevel.SAFE, RiskLevel.SAFE)) shouldBe RiskLevel.SAFE
            }
        }
        `when`("UNKNOWN 없이 여러 심각도가 섞여 있으면") {
            then("가장 심각한 위험도를 반환한다") {
                RiskLevel.aggregate(listOf(RiskLevel.SAFE, RiskLevel.CAUTION, RiskLevel.DANGER)) shouldBe RiskLevel.DANGER
            }
            then("최악값이 CAUTION 이면 CAUTION 이다") {
                RiskLevel.aggregate(listOf(RiskLevel.CAUTION, RiskLevel.SAFE)) shouldBe RiskLevel.CAUTION
            }
        }
        `when`("UNKNOWN 이 하나라도 포함되면") {
            then("최악값보다 UNKNOWN 을 우선해 UNKNOWN 을 반환한다") {
                RiskLevel.aggregate(listOf(RiskLevel.SAFE, RiskLevel.UNKNOWN)) shouldBe RiskLevel.UNKNOWN
            }
            then("DANGER 와 함께 있어도 UNKNOWN 을 반환한다") {
                RiskLevel.aggregate(listOf(RiskLevel.DANGER, RiskLevel.UNKNOWN)) shouldBe RiskLevel.UNKNOWN
            }
        }
    }
})
