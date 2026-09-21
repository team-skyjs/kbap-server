package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.data.domain.Limit

class ScanSuggestionMemberIdReader(
    private val settingRepository: NotificationSettingJpaRepository,
    private val pageSize: Int,
) : ItemStreamReader<Long> {
    private var cursor = 0L
    private val page = ArrayDeque<Long>()

    override fun open(executionContext: ExecutionContext) {
        cursor = 0L
        page.clear()
    }

    override fun read(): Long? {
        if (page.isEmpty()) {
            page.addAll(settingRepository.findMemberIdsByNewsTrueAfter(cursor, Limit.of(pageSize)))
            cursor = page.lastOrNull() ?: cursor
        }
        return page.removeFirstOrNull()
    }
}
