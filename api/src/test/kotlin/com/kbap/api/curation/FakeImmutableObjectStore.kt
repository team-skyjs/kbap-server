package com.kbap.api.curation

import com.kbap.common.port.storage.ImmutableObjectMetadata
import com.kbap.common.port.storage.ImmutableObjectStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap

class FakeImmutableObjectStore : ImmutableObjectStore {
    val objects = ConcurrentHashMap<String, ImmutableObjectMetadata>()
    val bytes = ConcurrentHashMap<String, ByteArray>()
    val immutablePuts = ConcurrentHashMap<String, String>()
    var beforeCopy: (() -> Unit)? = null
    var copyCount = 0
    override fun inspect(path: String) = objects[path]
    override fun copyIfUnchanged(source: String, destination: String, fingerprint: String) {
        beforeCopy?.invoke()
        val value = requireNotNull(objects[source])
        check(value.fingerprint == fingerprint)
        check(objects.putIfAbsent(destination, value) == null)
        bytes[source]?.let { bytes[destination] = it }
        copyCount++
    }
    override fun read(path: String): ByteArray = requireNotNull(bytes[path]) { "no bytes: $path" }
    override fun putImmutable(path: String, bytes: ByteArray, contentType: String) {
        this.bytes[path] = bytes
        objects[path] = ImmutableObjectMetadata(contentType, bytes.size.toLong(), "put-${bytes.size}")
        immutablePuts[path] = contentType
    }
    fun reset() { objects.clear(); bytes.clear(); immutablePuts.clear(); beforeCopy = null; copyCount = 0 }
}
@Configuration
class FakeImmutableObjectStoreConfig {
    @Bean fun fakeImmutableObjectStore() = FakeImmutableObjectStore()
}
