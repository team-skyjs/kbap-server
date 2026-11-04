package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.EntityStatus
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminProcessedFoodStatusService(
    private val products: ProcessedFoodProductJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun changeStatus(id: Long, status: EntityStatus, adminId: Long): AdminProcessedFoodStatusResponse {
        val current = products.findStatusForUpdateById(id) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
        if (current == status.name) return AdminProcessedFoodStatusResponse(id, status, changed = false)
        when (status) {
            EntityStatus.DELETED -> products.hide(id)
            EntityStatus.ACTIVE -> products.restore(id, products.findLastCompletedMirrorStartedAt() ?: LocalDateTime.now())
        }
        log.info("가공식품 상태 변경 id={} {} -> {} adminId={}", id, current, status, adminId)
        return AdminProcessedFoodStatusResponse(id, status, changed = true)
    }
}
