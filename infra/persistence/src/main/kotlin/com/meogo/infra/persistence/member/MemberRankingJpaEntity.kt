package com.meogo.infra.persistence.member

import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "member_ranking",
    uniqueConstraints = [UniqueConstraint(name = "uk_member_ranking_member", columnNames = ["member_id"])],
)
class MemberRankingJpaEntity(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "scan_count", nullable = false)
    var scanCount: Int = 0,
) : BaseEntity()
