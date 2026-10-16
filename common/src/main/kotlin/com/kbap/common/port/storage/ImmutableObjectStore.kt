package com.kbap.common.port.storage

interface ImmutableObjectStore {
    fun inspect(path: String): ImmutableObjectMetadata?
    fun copyIfUnchanged(source: String, destination: String, fingerprint: String)
}

data class ImmutableObjectMetadata(val contentType: String, val sizeBytes: Long, val fingerprint: String)
