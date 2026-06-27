package com.meogo.domain.scan

interface MenuScanRepository {
    fun save(menuScan: MenuScan): MenuScan

    fun findById(scanId: Long): MenuScan?
}
