package com.kbap.api.infra.storage

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import java.util.function.Consumer

class S3ImmutableObjectStoreTest : BehaviorSpec({
    given("S3 조건부 최종 복사") {
        `when`("HEAD에서 본 ETag로 복사하면") {
            then("같은 fingerprint 조건과 정확한 목적지로 SDK를 호출한다") {
                val client = mock(S3Client::class.java)
                var captured: CopyObjectRequest? = null
                doAnswer { invocation ->
                    val builder = HeadObjectRequest.builder()
                    invocation.getArgument<Consumer<HeadObjectRequest.Builder>>(0).accept(builder)
                    builder.build().key() shouldBe "curation/dev/staging/source"
                    HeadObjectResponse.builder().contentType("image/png").contentLength(100).eTag("\"etag\"").build()
                }.`when`(client).headObject(any<Consumer<HeadObjectRequest.Builder>>())
                doAnswer { invocation ->
                    val builder = CopyObjectRequest.builder()
                    invocation.getArgument<Consumer<CopyObjectRequest.Builder>>(0).accept(builder)
                    captured = builder.build()
                    CopyObjectResponse.builder().build()
                }.`when`(client).copyObject(any<Consumer<CopyObjectRequest.Builder>>())
                val store = S3ImmutableObjectStore(client, "bucket")
                val metadata = store.inspect("curation/dev/staging/source")!!
                store.copyIfUnchanged("curation/dev/staging/source", "curation/dev/final/unique", metadata.fingerprint)
                captured!!.copySourceIfMatch() shouldBe "\"etag\""
                captured!!.key() shouldBe "curation/dev/final/unique"
                captured!!.copySource() shouldBe "bucket%2Fcuration%2Fdev%2Fstaging%2Fsource"
            }
        }
    }
})
