package com.kbap.api.infra.storage

import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration

class S3StorageObjectStore(
    private val s3Client: S3Client,
    private val bucket: String,
) : StorageObjectStore {
    companion object {
        // 부트앱 config 가 AWS 타입을 직접 알지 않도록 S3Client 조립을 어댑터 안에 가둔다(:infra:auth Firebase 팩토리 패턴).
        fun create(region: String, bucket: String): StorageObjectStore =
            S3StorageObjectStore(S3Client.builder().region(Region.of(region)).build(), bucket)

        private val DELETE_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val LIST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }

    override fun head(path: String): StorageObjectMetadata? =
        try {
            val response = s3Client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(path).build(),
            )
            StorageObjectMetadata(
                contentType = response.contentType() ?: "",
                sizeBytes = response.contentLength(),
                lastModified = response.lastModified(),
            )
        } catch (e: NoSuchKeyException) {
            null
        }

    override fun delete(path: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(path)
                .overrideConfiguration { it.apiCallTimeout(DELETE_TIMEOUT) }
                .build(),
        )
    }

    override fun list(prefix: String, afterPath: String?, limit: Int): List<StoredObject> =
        s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .startAfter(afterPath)
                .maxKeys(limit)
                .overrideConfiguration { it.apiCallTimeout(LIST_TIMEOUT) }
                .build(),
        ).contents().map { StoredObject(path = it.key(), lastModified = it.lastModified()) }

    override fun put(path: String, bytes: ByteArray, contentType: String) {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(path).contentType(contentType).build(),
            RequestBody.fromBytes(bytes),
        )
    }
}
