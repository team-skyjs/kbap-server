package com.meogo.api.persistence.scan

import com.meogo.api.scan.MenuScan
import com.meogo.api.scan.MenuScanRepository
import org.springframework.stereotype.Repository

@Repository
class MenuScanRepositoryAdapter(
    private val jpaRepository: MenuScanJpaRepository,
) : MenuScanRepository {
    override fun save(menuScan: MenuScan): MenuScan =
        jpaRepository.save(MenuScanJpaEntity.from(menuScan)).toDomain()

    override fun findById(scanId: Long): MenuScan? =
        jpaRepository.findByIdWithItems(scanId)?.toDomain()
}
