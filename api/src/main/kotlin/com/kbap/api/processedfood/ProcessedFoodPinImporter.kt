package com.kbap.api.processedfood

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.port.storage.StorageObjectStore
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ProcessedFoodPinImporter(
    private val storage: StorageObjectStore,
    private val translations: ProcessedFoodTranslationService,
    @Value("\${kbap.storage.key-prefix:local}") keyPrefix: String,
    @Value("\${kbap.processed-food.pin-import.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = "$keyPrefix/$ROOT"

    @Scheduled(fixedDelayString = "\${kbap.processed-food.pin-import.interval:PT5M}", initialDelayString = "\${kbap.processed-food.pin-import.initial-delay:PT1M}")
    @SchedulerLock(name = "processed-food-pin-import", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    fun scheduledRun() {
        if (enabled) runOnce()
    }

    fun runOnce(): Int {
        val files = storage.list("$root/pending/", null, MAX_FILES_PER_RUN).map { it.path }.filter { it.endsWith(".json") }
        files.forEach { path -> runCatching { import(path) }.onFailure { log.warn("번역 고정 파일 처리 실패 — 다음 실행에서 다시 본다 path={}", path, it) } }
        return files.size
    }

    private fun import(path: String) {
        val name = path.substringAfterLast('/')
        val bytes = storage.get(path) ?: return
        val file = runCatching { mapper.readValue<PinImportFile>(bytes) }.getOrElse {
            return finish(path, name, bytes, FAILED, mapOf("error" to "INVALID_JSON", "detail" to (it.message?.take(MAX_DETAIL) ?: "")))
        }
        if (file.items.size > MAX_ITEMS_PER_FILE) return finish(path, name, bytes, FAILED, mapOf("error" to "TOO_MANY_ITEMS", "items" to file.items.size, "max" to MAX_ITEMS_PER_FILE))
        val dryRun = name.endsWith(DRY_RUN_SUFFIX)
        var applied = 0
        var unchanged = 0
        val rejections = mutableListOf<ProcessedFoodPinRejection>()
        file.items.chunked(ProcessedFoodTranslationService.MAX_PINS_PER_REQUEST).forEachIndexed { chunkIndex, chunk ->
            val result = translations.pin(file.kind, dryRun, chunk)
            applied += result.applied
            unchanged += result.unchanged
            rejections += result.rejections.map { it.copy(index = it.index + chunkIndex * ProcessedFoodTranslationService.MAX_PINS_PER_REQUEST) }
        }
        log.info("번역 고정 파일 처리 path={} dryRun={} applied={} unchanged={} rejected={}", path, dryRun, applied, unchanged, rejections.size)
        finish(
            path, name, bytes, DONE,
            mapOf("dryRun" to dryRun, "kind" to file.kind, "items" to file.items.size, "applied" to applied, "unchanged" to unchanged, "rejected" to rejections.size, "rejections" to rejections),
        )
    }

    private fun finish(path: String, name: String, bytes: ByteArray, folder: String, result: Map<String, Any?>) {
        storage.put("$root/$folder/$name.result.json", mapper.writeValueAsBytes(result), JSON)
        storage.put("$root/$folder/$name", bytes, JSON)
        storage.delete(path)
    }

    private data class PinImportFile(val kind: ProcessedFoodPinKind = ProcessedFoodPinKind.NAME, val items: List<ProcessedFoodPinItem> = emptyList())

    companion object {
        const val ROOT = "admin-imports/translation-overrides"
        private const val DONE = "done"
        private const val FAILED = "failed"
        private const val DRY_RUN_SUFFIX = ".dryrun.json"
        private const val JSON = "application/json"
        const val MAX_ITEMS_PER_FILE = 5_000
        private const val MAX_FILES_PER_RUN = 50
        private const val MAX_DETAIL = 300
        private val mapper = jacksonObjectMapper()
    }
}
