package com.kbap.api.infra.storage

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration

class S3PresignedUploadAdapterTest : BehaviorSpec({
    val presigner = S3Presigner.builder()
        .region(Region.AP_NORTHEAST_2)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key", "test-secret-key")),
        )
        .build()
    val port = S3PresignedUploadAdapter(presigner, bucket = "kbap-test-bucket", publicBaseUrl = "https://cdn.test")

    given("S3 presigned 업로드 발급 — 로컬 SigV4 서명(네트워크 없음)") {
        `when`("객체 키·Content-Type·크기·TTL 로 발급하면") {
            then("서명된 업로드 PUT URL 과 안정 공개 URL 을 만든다") {
                val key = "menu-scan/2026/07/15/42/3f2ac9d1-abc.jpg"

                val result = port.issue(key, "image/jpeg", 384512L, Duration.ofMinutes(5))

                result.uploadUrl shouldContain key
                result.uploadUrl shouldContain "X-Amz-Signature"
                result.uploadUrl shouldContain "kbap-test-bucket"
                result.publicUrl shouldBe "https://cdn.test/$key"
                result.objectKey shouldBe key
                result.requiredHeaders["Content-Type"] shouldBe "image/jpeg"
                result.requiredHeaders["Content-Length"] shouldBe "384512"
            }
        }

        `when`("공개 베이스 URL 이 슬래시로 끝나도") {
            then("공개 URL 에 슬래시가 중복되지 않는다") {
                val trailingPort = S3PresignedUploadAdapter(presigner, bucket = "kbap-test-bucket", publicBaseUrl = "https://cdn.test/")
                val result = trailingPort.issue("menu-scan/x.jpg", "image/png", 100L, Duration.ofMinutes(5))
                result.publicUrl shouldBe "https://cdn.test/menu-scan/x.jpg"
            }
        }
    }
})
