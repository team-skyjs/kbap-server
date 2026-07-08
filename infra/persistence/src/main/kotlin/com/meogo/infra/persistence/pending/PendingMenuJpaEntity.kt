package com.meogo.infra.persistence.pending

import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "pending_menus")
class PendingMenuJpaEntity(
    @Column(name = "standard_name", nullable = false, length = 100, unique = true)
    var standardName: String = "",

    @Column(name = "queue_status", nullable = false, length = 20)
    var queueStatus: String = "PENDING",
) : BaseEntity()
