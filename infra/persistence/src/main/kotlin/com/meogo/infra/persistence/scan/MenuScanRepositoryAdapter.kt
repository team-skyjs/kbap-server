package com.meogo.infra.persistence.scan

import com.meogo.core.scan.MenuScan
import com.meogo.core.scan.MenuScanRepository
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
