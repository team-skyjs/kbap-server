package com.kbap.common.domain.member.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberAdRewardUnlockTest : BehaviorSpec({
    given("광고 적립 수로 스캔 해제 판정") {
        `when`("적립이 해제 기준(3) 미만이면") {
            then("해제하지 않는다") {
                val member = Member()
                member.unlockScanIfAdRewardsReached(2)
                member.scanUnlocked shouldBe false
            }
        }
        `when`("적립이 기준 이상이면") {
            then("해제한다 — 이미 해제된 회원은 그대로 true") {
                val member = Member()
                member.unlockScanIfAdRewardsReached(3)
                member.scanUnlocked shouldBe true
                member.unlockScanIfAdRewardsReached(4)
                member.scanUnlocked shouldBe true
            }
        }
        `when`("해제 기준 상수를 보면") {
            then("3 이다 — 리뷰 1개와 같은 가치") {
                Member.AD_REWARDS_FOR_UNLOCK shouldBe 3
            }
        }
    }
})
