package com.kbap.common.domain.member.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberAdRewardUnlockTest : BehaviorSpec({
    given("광고 적립 수로 스캔 해제 판정") {
        `when`("적립이 해제 기준(3) 미만이면") {
            then("해제하지 않고 남은 수를 돌려준다") {
                Member.adRewardsUnlockScan(2) shouldBe false
                Member.adRewardsRemainingForUnlock(2, scanUnlocked = false) shouldBe 1
                Member.adRewardsRemainingForUnlock(0, scanUnlocked = false) shouldBe 3
            }
        }
        `when`("적립이 기준 이상이면") {
            then("해제하고 남은 수는 0 이다") {
                Member.adRewardsUnlockScan(3) shouldBe true
                Member.adRewardsUnlockScan(4) shouldBe true
                Member.adRewardsRemainingForUnlock(4, scanUnlocked = true) shouldBe 0
            }
        }
        `when`("리뷰 등으로 이미 해제된 회원이면") {
            then("적립이 적어도 남은 수는 0 이다 — 앱이 해제 대기 카드를 그리지 않게") {
                Member.adRewardsRemainingForUnlock(1, scanUnlocked = true) shouldBe 0
            }
        }
        `when`("해제 기준 상수를 보면") {
            then("3 이다 — 리뷰 1개와 같은 가치") {
                Member.AD_REWARDS_FOR_UNLOCK shouldBe 3
            }
        }
    }
})
