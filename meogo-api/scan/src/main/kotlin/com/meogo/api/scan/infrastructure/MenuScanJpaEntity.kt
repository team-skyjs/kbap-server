package com.meogo.api.scan.infrastructure

import com.meogo.api.persistence.BaseEntity
import com.meogo.api.scan.MenuScan
import com.meogo.api.scan.ScanStatus
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
    @Column(name = "scan_status", nullable = false, length = 20)
    var scanStatus: String = "",

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "scan_id", nullable = false)
    var items: MutableList<ScannedMenuItemJpaEntity> = mutableListOf(),
) : BaseEntity() {
    fun toDomain(): MenuScan =
        MenuScan.reconstitute(
            id = id,
            status = ScanStatus.valueOf(scanStatus),
            items = items
                .sortedBy { it.receivedOrder }
                .map { it.toDomain() },
        )

    companion object {
        fun from(menuScan: MenuScan): MenuScanJpaEntity =
            MenuScanJpaEntity(
                scanStatus = menuScan.status.name,
                items = menuScan.items.map { ScannedMenuItemJpaEntity.from(it) }.toMutableList(),
            )
    }
}
