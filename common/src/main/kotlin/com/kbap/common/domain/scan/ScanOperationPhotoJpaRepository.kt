package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanOperationPhoto
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScanOperationPhotoJpaRepository : JpaRepository<ScanOperationPhoto, Long> {
    fun findByOperationIdOrderBySelectionIndex(operationId: Long): List<ScanOperationPhoto>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ScanOperationPhoto p where p.operationId = :operationId order by p.selectionIndex")
    fun findAllForUpdate(@Param("operationId") operationId: Long): List<ScanOperationPhoto>
}
