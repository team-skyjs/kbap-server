package com.meogo.api.scan.infrastructure

import com.meogo.api.persistence.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(name = "menu_scan")
class MenuScanJpaEntity(
    @Column(name = "scan_status", nullable = false)
    var scanStatus: String = "",

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "scan_id", nullable = false)
    var items: MutableList<ScannedMenuItemJpaEntity> = mutableListOf(),
) : BaseEntity()
