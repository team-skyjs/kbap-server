package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "scan_history_source")
class ScanHistorySource(
    @Column(name = "history_id", nullable = false)
    val historyId: Long = 0,

    @Column(name = "occurrence_id", nullable = false)
    val occurrenceId: Long = 0,
) : BaseEntity()
