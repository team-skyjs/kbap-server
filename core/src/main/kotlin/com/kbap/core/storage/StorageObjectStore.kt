package com.kbap.core.storage

// 오브젝트 스토리지 접근 seam(구현은 :infra:storage). 소비 도메인 서비스가 업로드된 오브젝트를
// 검증(head)·정리(delete)하는 유일 창구다.
interface StorageObjectStore {
    // 오브젝트 메타데이터. 존재하지 않으면 null.
    fun head(path: String): StorageObjectMetadata?

    fun delete(path: String)
}

data class StorageObjectMetadata(
    val contentType: String,
    val sizeBytes: Long,
)
