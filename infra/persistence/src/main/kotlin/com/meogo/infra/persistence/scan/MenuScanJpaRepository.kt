package com.meogo.infra.persistence.scan

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MenuScanJpaRepository : JpaRepository<MenuScanJpaEntity, Long> {
    @Query("select distinct ms from MenuScanJpaEntity ms left join fetch ms.items where ms.id = :id")
    fun findByIdWithItems(@Param("id") id: Long): MenuScanJpaEntity?
}
