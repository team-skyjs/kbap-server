package com.kbap.batch.notification

import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.stereotype.Component

@Component
@JobScope
class ScanSuggestionCandidateBuffer {
    private val memberIds = ArrayDeque<Long>()

    fun load(candidates: Collection<Long>) {
        memberIds.addAll(candidates)
    }

    fun poll(): Long? = memberIds.removeFirstOrNull()
}
