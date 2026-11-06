package com.kbap.api.processedfood

import com.kbap.common.port.llm.MenuPhotoClassificationFailed
import com.kbap.common.port.llm.MenuPhotoClassifier
import com.kbap.common.port.llm.MenuPhotoVerdict
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class MenuFastPath(
    classifierProvider: ObjectProvider<MenuPhotoClassifier>,
    private val meters: MeterRegistry,
    @Value("\${kbap.processed-food.vision.menu-fast-path.enabled:false}") private val enabled: Boolean,
    @Value("\${kbap.processed-food.vision.menu-fast-path.member-ids:}") memberIds: List<Long>,
    @Value("\${kbap.processed-food.vision.menu-fast-path.min-probability:0.98}") private val minProbability: Double,
    @Value("\${kbap.processed-food.vision.menu-fast-path.budget:1s}") private val budget: Duration,
    @Value("\${kbap.processed-food.vision.menu-fast-path.max-concurrent:4}") maxConcurrent: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val classifier: MenuPhotoClassifier? = classifierProvider.getIfAvailable()
    private val allowedMembers: Set<Long> = memberIds.toSet()
    private val slots = Semaphore(maxConcurrent)
    private val executor = Executors.newFixedThreadPool(maxConcurrent) { runnable -> Thread(runnable, "menu-fast-path").apply { isDaemon = true } }

    fun tryMenu(memberId: Long, imagePath: String, requested: Boolean): ProcessedFoodPhotoReading? {
        val classifier = classifier ?: return null
        if (!enabled || !requested || memberId !in allowedMembers) return null
        if (!slots.tryAcquire()) return fallback("busy", null)
        val sample = Timer.start(meters)
        val future = try {
            executor.submit<MenuPhotoVerdict> {
                try {
                    classifier.classify(imagePath)
                } finally {
                    slots.release()
                }
            }
        } catch (e: RuntimeException) {
            slots.release()
            return fallback("busy", sample)
        }
        val verdict = try {
            future.get(budget.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            return fallback("timeout", sample)
        } catch (e: ExecutionException) {
            return fallback((e.cause as? MenuPhotoClassificationFailed)?.reason ?: "error", sample)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            return fallback("interrupted", sample)
        }
        if (verdict !is MenuPhotoVerdict.Menu) return fallback("not_menu", sample)
        if (verdict.probability < minProbability) return fallback("low_probability", sample)
        sample.stop(meters.timer("processed_food.vision.menu_fast_path.latency", "result", "menu"))
        meters.counter("processed_food.vision.menu_fast_path", "result", "menu", "reason", "none").increment()
        return ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.MENU, verdict.probability)
    }

    private fun fallback(reason: String, sample: Timer.Sample?): ProcessedFoodPhotoReading? {
        sample?.stop(meters.timer("processed_food.vision.menu_fast_path.latency", "result", "fallback"))
        meters.counter("processed_food.vision.menu_fast_path", "result", "fallback", "reason", reason).increment()
        log.info("메뉴판 바로가기 폴백 reason={}", reason)
        return null
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
