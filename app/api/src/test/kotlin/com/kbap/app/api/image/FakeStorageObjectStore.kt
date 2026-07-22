package com.kbap.app.api.image

import com.kbap.core.storage.StorageObjectMetadata
import com.kbap.core.storage.StorageObjectStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 테스트용 페이크 스토리지 — 실 S3 없이 head 응답을 주입하고 delete 호출을 기록한다.
class FakeStorageObjectStore : StorageObjectStore {
    val heads: MutableMap<String, StorageObjectMetadata> = mutableMapOf()
    val deleted: MutableList<String> = mutableListOf()
    val headCalls: MutableList<String> = mutableListOf()

    fun stub(path: String, contentType: String, sizeBytes: Long) {
        heads[path] = StorageObjectMetadata(contentType, sizeBytes)
    }

    override fun put(path: String, bytes: ByteArray, contentType: String) {
        heads[path] = StorageObjectMetadata(contentType, bytes.size.toLong())
    }

    override fun head(path: String): StorageObjectMetadata? {
        headCalls.add(path)
        return heads[path]
    }

    override fun delete(path: String) {
        deleted.add(path)
        heads.remove(path)
    }
}

// 전 app:api 통합 테스트가 공유하는 페이크 — ImageUploadService 가 StorageObjectStore 빈을 요구하므로
// 항상 스캔되는 @Configuration 으로 제공한다(실 StorageConfig 는 kbap.storage.enabled 로 꺼져 있어 충돌 없음).
@Configuration
class FakeStorageConfig {
    @Bean
    fun fakeStorageObjectStore(): FakeStorageObjectStore = FakeStorageObjectStore()
}
