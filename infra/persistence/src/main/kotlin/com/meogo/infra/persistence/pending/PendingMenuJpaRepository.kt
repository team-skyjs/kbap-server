package com.meogo.infra.persistence.pending

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PendingMenuJpaRepository : JpaRepository<PendingMenuJpaEntity, Long> {
    @Modifying
    @Query(
        value = """
            INSERT INTO pending_menus (standard_name, queue_status, status, created_at, updated_at)
            VALUES (:name, 'PENDING', 'ACTIVE', NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun upsert(@Param("name") name: String)
}
