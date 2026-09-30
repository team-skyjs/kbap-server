package com.kbap.api.image

import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.port.storage.StorageObjectStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 테스트용 페이크 스토리지 — 실 S3 없이 head 응답을 주입하고 delete 호출을 기록한다.
class FakeStorageObjectStore : StorageObjectStore {
    val heads: MutableMap<String, StorageObjectMetadata> = java.util.concurrent.ConcurrentHashMap()
    val deleted: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val headCalls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    @Volatile var headDelayMillis: Long = 0
    @Volatile var failDeletes: Boolean = false
    val lastModified: MutableMap<String, java.time.Instant> = java.util.concurrent.ConcurrentHashMap()

    fun stub(path: String, contentType: String, sizeBytes: Long, modifiedAt: java.time.Instant = java.time.Instant.now()) {
        heads[path] = StorageObjectMetadata(contentType, sizeBytes, modifiedAt)
        lastModified[path] = modifiedAt
    }

    override fun list(prefix: String, afterPath: String?, limit: Int): List<com.kbap.common.port.storage.StoredObject> {
        val page = heads.keys.filter { it.startsWith(prefix) && (afterPath == null || it > afterPath) }.sorted().take(limit)
            .map { com.kbap.common.port.storage.StoredObject(it, lastModified[it] ?: java.time.Instant.now()) }
        return page
    }

    override fun put(path: String, bytes: ByteArray, contentType: String) {
        heads[path] = StorageObjectMetadata(contentType, bytes.size.toLong(), java.time.Instant.now())
        lastModified[path] = java.time.Instant.now()
    }

    override fun head(path: String): StorageObjectMetadata? {
        if (headDelayMillis > 0) Thread.sleep(headDelayMillis)
        headCalls.add(path)
        return heads[path]
    }

    override fun delete(path: String) {
        if (failDeletes) throw IllegalStateException("테스트 — 스토리지 삭제 실패")
        deleted.add(path)
        heads.remove(path)
        lastModified.remove(path)
    }
}

// 전 api 통합 테스트가 공유하는 페이크 — ImageUploadService 가 StorageObjectStore 빈을 요구하므로
// 항상 스캔되는 @Configuration 으로 제공한다(실 StorageConfig 는 kbap.storage.enabled 로 꺼져 있어 충돌 없음).
@Configuration
class FakeStorageConfig {
    @Bean
    fun fakeStorageObjectStore(): FakeStorageObjectStore = FakeStorageObjectStore()
}
