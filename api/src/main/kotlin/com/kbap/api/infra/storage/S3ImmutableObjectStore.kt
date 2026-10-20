package com.kbap.api.infra.storage

import com.kbap.common.port.storage.ImmutableObjectMetadata
import com.kbap.common.port.storage.ImmutableObjectStore
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

class S3ImmutableObjectStore(private val client: S3Client, private val bucket: String) : ImmutableObjectStore {
    override fun inspect(path: String): ImmutableObjectMetadata? = try {
        val head = client.headObject { it.bucket(bucket).key(path).overrideConfiguration { c -> c.apiCallTimeout(Duration.ofSeconds(15)) } }
        ImmutableObjectMetadata(head.contentType().orEmpty(), head.contentLength(), requireNotNull(head.eTag()))
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw e
    }

    override fun copyIfUnchanged(source: String, destination: String, fingerprint: String) {
        client.copyObject {
            it.bucket(bucket).key(destination)
                .copySource(URLEncoder.encode("$bucket/$source", StandardCharsets.UTF_8).replace("+", "%20"))
                .copySourceIfMatch(fingerprint)
                .overrideConfiguration { c -> c.apiCallTimeout(Duration.ofSeconds(30)) }
        }
    }

    companion object {
        fun create(region: String, bucket: String): ImmutableObjectStore = S3ImmutableObjectStore(S3Client.builder().region(Region.of(region)).build(), bucket)
    }
}
