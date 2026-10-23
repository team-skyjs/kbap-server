package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanOperation
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScanOperationJpaRepository : JpaRepository<ScanOperation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ScanOperation o where o.memberId = :memberId and o.keyNamespace = :keyNamespace and o.requestKey = :requestKey")
    fun findForUpdate(
        @Param("memberId") memberId: Long,
        @Param("keyNamespace") keyNamespace: String,
        @Param("requestKey") requestKey: ByteArray,
    ): ScanOperation?

    fun findByMemberIdAndKeyNamespaceAndRequestKey(memberId: Long, keyNamespace: String, requestKey: ByteArray): ScanOperation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ScanOperation o where o.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): ScanOperation?
}
