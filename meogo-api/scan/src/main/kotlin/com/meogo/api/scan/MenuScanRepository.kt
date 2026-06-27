package com.meogo.api.scan

interface MenuScanRepository {
    fun save(menuScan: MenuScan): MenuScan

    fun findById(scanId: Long): MenuScan?
}
