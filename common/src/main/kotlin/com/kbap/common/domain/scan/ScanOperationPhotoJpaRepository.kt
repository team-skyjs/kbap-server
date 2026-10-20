package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanOperationPhoto
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StalePendingPhoto {
    val memberId: Long
    val requestKey: ByteArray
    val selectionIndex: Int
}

interface ScanOperationPhotoJpaRepository : JpaRepository<ScanOperationPhoto, Long> {
    @Query(
        """
        select o.memberId as memberId, o.requestKey as requestKey, p.selectionIndex as selectionIndex
        from ScanOperationPhoto p, ScanOperation o
        where o.id = p.operationId and o.keyNamespace = :namespace and o.lifecycle = 'ACTIVE'
          and o.expiresAt > :now and o.createdAt < :createdBefore
          and p.executionState = com.kbap.common.domain.scan.model.ScanPhotoState.PENDING
        order by o.id, p.selectionIndex
        """,
    )
    fun findStalePending(
        @Param("namespace") namespace: String,
        @Param("now") now: java.time.LocalDateTime,
        @Param("createdBefore") createdBefore: java.time.LocalDateTime,
        pageable: org.springframework.data.domain.Pageable,
    ): List<StalePendingPhoto>

    fun findByOperationIdOrderBySelectionIndex(operationId: Long): List<ScanOperationPhoto>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ScanOperationPhoto p where p.operationId = :operationId order by p.selectionIndex")
    fun findAllForUpdate(@Param("operationId") operationId: Long): List<ScanOperationPhoto>
}
