package com.kbap.api.curation

import com.kbap.common.port.storage.ImmutableObjectMetadata
import com.kbap.common.port.storage.ImmutableObjectStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap

class FakeImmutableObjectStore : ImmutableObjectStore {
    val objects = ConcurrentHashMap<String, ImmutableObjectMetadata>()
    var beforeCopy: (() -> Unit)? = null
    var copyCount = 0
    override fun inspect(path: String) = objects[path]
    override fun copyIfUnchanged(source: String, destination: String, fingerprint: String) {
        beforeCopy?.invoke()
        val value = requireNotNull(objects[source])
        check(value.fingerprint == fingerprint)
        check(objects.putIfAbsent(destination, value) == null)
        copyCount++
    }
    fun reset() { objects.clear(); beforeCopy = null; copyCount = 0 }
}
@Configuration
class FakeImmutableObjectStoreConfig {
    @Bean fun fakeImmutableObjectStore() = FakeImmutableObjectStore()
}
