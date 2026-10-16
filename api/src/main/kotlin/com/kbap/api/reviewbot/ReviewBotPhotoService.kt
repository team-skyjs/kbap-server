package com.kbap.api.reviewbot

import com.kbap.api.image.ImageUploadProperties
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.storage.StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class ReviewBotPhotoService(
    private val catalog: ReviewBotPhotoCatalog,
    private val storage: StorageObjectStore,
    private val accounts: ReviewBotAccountService,
    properties: ImageUploadProperties,
    @Value("\${kbap.review-bot.photo-sync-enabled:false}") private val syncEnabled: Boolean,
) {
    val prefix = properties.keyPrefix.trim('/').let { if (it.isEmpty()) "images/review-bot/" else "$it/images/review-bot/" }
    private val filePattern = Regex(Regex.escape(prefix) + "[0-9a-f]{12}/[0-9]+\\.webp")

    init {
        require(properties.keyPrefix.trim('/').split('/').all { it.isEmpty() || it.matches(Regex("[A-Za-z0-9_-]+")) })
    }

    fun getInventory(): ReviewBotPhotoInventory {
        val stored = linkedSetOf<String>()
        var after: String? = null
        while (true) {
            val page = storage.list(prefix, after, 500)
            if (page.isEmpty()) break
            val paths = page.map { it.path }
            check(paths.all { it.startsWith(prefix) } && paths == paths.sorted() && paths.distinct().size == paths.size)
            check(after == null || paths.first() > after)
            stored.addAll(paths)
            after = paths.last()
        }
        val records = catalog.getRecords(prefix).associateBy { it.path }
        val ordinaryReferences = catalog.getOrdinaryReferencedPaths((stored + records.keys).filter { filePattern.matches(it) })
        val held = mutableListOf<ReviewBotPhotoHold>()
        val available = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        (stored + records.keys).sorted().forEach { path ->
            val row = records[path]
            val reason = when {
                !filePattern.matches(path) -> "INVALID_PATH"
                path in ordinaryReferences -> "ORDINARY_REFERENCE"
                row != null && !row.usableOwner -> "OWNER_OR_STATUS"
                path !in stored -> "MISSING_OBJECT"
                else -> null
            }
            if (reason != null) held += ReviewBotPhotoHold(path, reason)
            else if (row == null) unregistered += path
            else available += path
        }
        return ReviewBotPhotoInventory(available, unregistered, held)
    }

    @Synchronized
    fun sync(): ReviewBotPhotoSyncResult {
        if (!syncEnabled) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val inventory = getInventory()
        val owner = accounts.getBots().minByOrNull { it.id } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        var registered = 0
        val held = inventory.held.toMutableList()
        inventory.unregistered.forEach { path ->
            val metadata = storage.head(path)
            if (metadata == null || metadata.contentType != "image/webp" || metadata.sizeBytes <= 0) {
                held += ReviewBotPhotoHold(path, "MISSING_OR_INVALID_METADATA")
            } else {
                val added = try { catalog.register(owner.id, path, metadata) } catch (_: DataIntegrityViolationException) { false }
                if (added) registered++ else held += ReviewBotPhotoHold(path, "REGISTRATION_CONFLICT")
            }
        }
        return ReviewBotPhotoSyncResult(registered, held)
    }

    fun getAvailablePaths(koreanName: String): List<String> {
        val foodPrefix = prefix + keyOf(koreanName) + "/"
        return getInventory().available.filter { it.startsWith(foodPrefix) }.filter { path ->
            storage.head(path)?.let { it.contentType == "image/webp" && it.sizeBytes > 0 } == true
        }
    }

    companion object {
        fun keyOf(koreanName: String): String = MessageDigest.getInstance("SHA-256").digest(koreanName.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
    }
}

data class ReviewBotPhotoHold(val path: String, val reason: String)
data class ReviewBotPhotoInventory(val available: List<String>, val unregistered: List<String>, val held: List<ReviewBotPhotoHold>)
data class ReviewBotPhotoSyncResult(val registeredCount: Int, val held: List<ReviewBotPhotoHold>)
