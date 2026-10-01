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
        set(NAME, Tags.of("cleanup", cleanup, "purpose", purpose, "kind", kind), value.toDouble())

    fun invalidate(cleanup: String, kind: String) =
        values.filterKeys { (name, tags) ->
            name == NAME && tags.any { it.key == "cleanup" && it.value == cleanup } && tags.any { it.key == "kind" && it.value == kind }
        }.values.forEach { it.set(Double.NaN.toRawBits()) }

    fun markRun(cleanup: String) = set(LAST_RUN, Tags.of("cleanup", cleanup), Instant.now().epochSecond.toDouble())

    private fun set(name: String, tags: Tags, value: Double) =
        values.computeIfAbsent(name to tags) { registry.gauge(name, tags, AtomicLong()) { Double.fromBits(it.get()) }!! }
            .set(value.toRawBits())

    companion object {
        const val NAME = "kbap.upload.cleanup"
        const val LAST_RUN = "kbap.upload.cleanup.last.run.seconds"
        const val RECORDED = "recorded"
        const val UNRECORDED = "unrecorded"
        const val ALL_PURPOSES = "all"
    }
}
