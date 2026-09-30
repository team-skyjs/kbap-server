package com.kbap.common.port.storage

interface StorageObjectStore {
    fun head(path: String): StorageObjectMetadata?

    fun delete(path: String)

    // 같은 path 로 다시 저장하면 덮어쓴다(멱등).
    fun put(path: String, bytes: ByteArray, contentType: String)

    fun list(prefix: String, afterPath: String?, limit: Int): List<StoredObject>
}

data class StoredObject(
    val path: String,
    val lastModified: java.time.Instant,
)

data class StorageObjectMetadata(
    val contentType: String,
    val sizeBytes: Long,
)
