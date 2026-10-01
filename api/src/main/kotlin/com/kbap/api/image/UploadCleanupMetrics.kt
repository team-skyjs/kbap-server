package com.kbap.api.image

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class UploadCleanupMetrics(private val registry: MeterRegistry) {
    private val values = ConcurrentHashMap<Pair<String, Tags>, AtomicLong>()

    fun record(cleanup: String, purpose: String, kind: String, value: Long) =
        gauge(NAME, Tags.of("cleanup", cleanup, "purpose", purpose, "kind", kind)).set(value)

    fun markRun(cleanup: String) = gauge(LAST_RUN, Tags.of("cleanup", cleanup)).set(Instant.now().epochSecond)

    private fun gauge(name: String, tags: Tags): AtomicLong =
        values.computeIfAbsent(name to tags) { registry.gauge(name, tags, AtomicLong())!! }

    companion object {
        const val NAME = "kbap.upload.cleanup"
        const val LAST_RUN = "kbap.upload.cleanup.last.run.seconds"
        const val RECORDED = "recorded"
        const val UNRECORDED = "unrecorded"
        const val ALL_PURPOSES = "all"
    }
}
