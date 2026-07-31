package com.kbap.common.domain.block.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "member_block")
class MemberBlock(
    @Column(nullable = false)
    val blockerMemberId: Long = 0,

    @Column(nullable = false)
    val blockedMemberId: Long = 0,
) : BaseEntity()
