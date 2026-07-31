package com.kbap.common.domain.block.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "member_block",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_member_block_pair", columnNames = ["blocker_member_id", "blocked_member_id"]),
    ],
)
class MemberBlock(
    @Column(nullable = false)
    val blockerMemberId: Long = 0,

    @Column(nullable = false)
    val blockedMemberId: Long = 0,
) : BaseEntity()
