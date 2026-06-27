package com.meogo.domain.scan.infrastructure

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "menu_scan")
class MenuScanJpaEntity(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @field:Column(nullable = false)
    var status: String = "",

    @field:Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @field:OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.EAGER, orphanRemoval = true)
    @field:JoinColumn(name = "scan_id", nullable = false)
    var items: MutableList<ScannedMenuItemJpaEntity> = mutableListOf(),
)
