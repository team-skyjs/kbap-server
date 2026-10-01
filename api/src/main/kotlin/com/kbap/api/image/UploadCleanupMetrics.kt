package com.kbap.api.image

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class UploadCleanupMetrics(private val registry: MeterRegistry) {
    fun record(job: String, purpose: String, kind: String, value: Long) = Unit

    fun markRun(job: String) = Unit

    companion object {
        const val NAME = "kbap.upload.cleanup"
        const val LAST_RUN = "kbap.upload.cleanup.last.run.seconds"
        const val RECORDED = "recorded"
        const val UNRECORDED = "unrecorded"
        const val ALL_PURPOSES = "all"
    }
}
