package com.kbap.api.image

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class UploadCleanupMetricsTest : BehaviorSpec({
    fun recordWithThrowawayMetrics(registry: MeterRegistry) {
        UploadCleanupMetrics(registry).record("unrecorded", "scans", "candidate_in_run", 3)
    }

    given("업로드 정리 게이지") {
        `when`("기록한 UploadCleanupMetrics 를 아무도 붙잡지 않은 채 GC 가 돌면") {
            then("게이지 값이 NaN 이 되지 않고 남는다(상태를 레지스트리가 강하게 붙잡는다)") {
                val registry = SimpleMeterRegistry()
                recordWithThrowawayMetrics(registry)

                repeat(5) {
                    System.gc()
                    Thread.sleep(20)
                }

                registry.get(UploadCleanupMetrics.NAME).tags("cleanup", "unrecorded", "purpose", "scans", "kind", "candidate_in_run").gauge().value() shouldBe 3.0
            }
        }
    }
})
