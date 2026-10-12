package com.kbap.common.domain.member.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "member_ad_reward",
    uniqueConstraints = [UniqueConstraint(name = "uk_member_ad_reward_key", columnNames = ["reward_key"])],
    indexes = [Index(name = "idx_member_ad_reward_member_created", columnList = "member_id, created_at")],
)
class MemberAdReward(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "reward_key", nullable = false, length = 128)
    var rewardKey: String = "",

    @Column(name = "ad_unit_id", nullable = false, length = 100)
    var adUnitId: String = "",
) : BaseEntity() {
    companion object {
        fun of(memberId: Long, rewardKey: String, adUnitId: String): MemberAdReward =
            MemberAdReward(memberId = memberId, rewardKey = rewardKey, adUnitId = adUnitId)
    }
}
