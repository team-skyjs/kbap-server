package com.meogo.infra.persistence.pending

import com.meogo.core.scan.PendingMenuRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class PendingMenuRepositoryAdapter(
    private val jpaRepository: PendingMenuJpaRepository,
) : PendingMenuRepository {
    @Transactional
    override fun enqueue(name: String) {
        require(name.isNotBlank()) { "대기열 등록 이름은 blank 일 수 없습니다" }
        jpaRepository.upsert(name.take(MAX_NAME_LENGTH))
    }

    companion object {
        private const val MAX_NAME_LENGTH = 255
    }
}
