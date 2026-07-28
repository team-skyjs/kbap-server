package com.kbap.common.port.storage

interface StorageObjectStore {
    fun head(path: String): StorageObjectMetadata?

    fun delete(path: String)

    // 같은 path 로 다시 저장하면 덮어쓴다(멱등).
    fun put(path: String, bytes: ByteArray, contentType: String)
}

data class StorageObjectMetadata(
    val contentType: String,
    val sizeBytes: Long,
)
