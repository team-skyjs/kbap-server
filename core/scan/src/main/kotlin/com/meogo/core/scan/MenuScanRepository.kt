package com.meogo.core.scan

interface MenuScanRepository {
    fun save(menuScan: MenuScan): MenuScan

    fun findById(scanId: Long): MenuScan?
}
