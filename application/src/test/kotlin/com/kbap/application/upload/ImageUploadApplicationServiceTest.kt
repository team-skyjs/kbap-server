package com.kbap.application.upload

import com.kbap.application.upload.dto.ImageUploadInput
import com.kbap.application.upload.dto.PresignedUpload
import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.time.Duration
import java.time.Instant

class ImageUploadApplicationServiceTest : BehaviorSpec({

    class RecordingPort : PresignedUploadPort {
        val keys = mutableListOf<String>()
        var lastContentType: String? = null
        var lastContentLength: Long? = null
        var lastTtl: Duration? = null

        override fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload {
            keys += key
            lastContentType = contentType
            lastContentLength = contentLength
            lastTtl = ttl
            return PresignedUpload(
                uploadUrl = "https://s3.example/$key?sig=x",
                requiredHeaders = mapOf("Content-Type" to contentType, "Content-Length" to contentLength.toString()),
                publicUrl = "https://cdn.test/$key",
                objectKey = key,
                expiresAt = Instant.EPOCH.plus(ttl),
            )
        }
    }

    fun properties(keyPrefix: String = "") = ImageUploadProperties(
        allowedContentTypes = setOf("image/jpeg", "image/png"),
        maxBytes = 1_000L,
        uploadTtl = Duration.ofMinutes(5),
        publicBaseUrl = "https://cdn.test",
        keyPrefix = keyPrefix,
    )

    fun input(
        memberId: Long = 42L,
        purpose: String = "MENU_SCAN",
        contentType: String = "image/jpeg",
        contentLength: Long = 500L,
    ) = ImageUploadInput(memberId, purpose, contentType, contentLength)

    given("이미지 업로드 URL 발급") {
        `when`("지원하지 않는 용도로 요청하면") {
            then("UPLOAD-002 로 거절한다") {
                val service = ImageUploadApplicationService(properties(), RecordingPort())
                val ex = shouldThrow<BusinessException> {
                    service.issueUploadUrl(input(purpose = "UNKNOWN"))
                }
                ex.errorCode shouldBe ErrorCode.UNSUPPORTED_UPLOAD_PURPOSE
            }
        }

        `when`("허용되지 않은 Content-Type 으로 요청하면") {
            then("UPLOAD-001 로 거절한다") {
                val service = ImageUploadApplicationService(properties(), RecordingPort())
                val ex = shouldThrow<BusinessException> {
                    service.issueUploadUrl(input(contentType = "image/gif"))
                }
                ex.errorCode shouldBe ErrorCode.UNSUPPORTED_IMAGE_CONTENT_TYPE
            }
        }

        `when`("허용 크기를 초과하면") {
            then("UPLOAD-003 로 거절한다") {
                val service = ImageUploadApplicationService(properties(), RecordingPort())
                val ex = shouldThrow<BusinessException> {
                    service.issueUploadUrl(input(contentLength = 1_001L))
                }
                ex.errorCode shouldBe ErrorCode.IMAGE_TOO_LARGE
            }
        }

        `when`("유효한 요청이면") {
            then("규격에 맞는 객체 키로 port 에 위임하고 결과를 반환한다") {
                val port = RecordingPort()
                val service = ImageUploadApplicationService(properties(), port)

                val result = service.issueUploadUrl(input(memberId = 1024L, contentType = "image/jpeg"))

                port.keys.single() shouldMatch Regex("""^images/scan/\d{4}/\d{2}/1024/[0-9a-f-]{36}\.jpg$""")
                port.lastContentType shouldBe "image/jpeg"
                port.lastContentLength shouldBe 500L
                port.lastTtl shouldBe Duration.ofMinutes(5)
                result.objectKey shouldBe port.keys.single()
                result.publicUrl shouldBe "https://cdn.test/${port.keys.single()}"
            }
        }

        `when`("PROFILE_IMAGE 용도로 요청하면") {
            then("객체 키가 profile 폴더 아래로 생성된다") {
                val port = RecordingPort()
                val service = ImageUploadApplicationService(properties(), port)

                service.issueUploadUrl(input(memberId = 7L, purpose = "PROFILE_IMAGE"))

                port.keys.single() shouldMatch Regex("""^images/profile/\d{4}/\d{2}/7/[0-9a-f-]{36}\.jpg$""")
            }
        }

        `when`("image/png 을 올리면") {
            then("객체 키 확장자가 png 다") {
                val port = RecordingPort()
                val service = ImageUploadApplicationService(properties(), port)
                service.issueUploadUrl(input(contentType = "image/png"))
                port.keys.single() shouldMatch Regex(""".*\.png$""")
            }
        }

        `when`("환경 접두가 dev 로 설정되면") {
            then("객체 키와 공개 URL 이 dev/ 접두로 시작한다") {
                val port = RecordingPort()
                val service = ImageUploadApplicationService(properties(keyPrefix = "dev"), port)

                val result = service.issueUploadUrl(input(memberId = 1024L))

                port.keys.single() shouldMatch Regex("""^dev/images/scan/\d{4}/\d{2}/1024/[0-9a-f-]{36}\.jpg$""")
                result.publicUrl shouldBe "https://cdn.test/${port.keys.single()}"
            }
        }

        `when`("접두가 dev/ 또는 /dev 처럼 슬래시를 포함하면") {
            then("동일하게 정규화되어 중복 슬래시 없이 dev/ 로 시작한다") {
                listOf("dev/", "/dev").forEach { prefix ->
                    val port = RecordingPort()
                    val service = ImageUploadApplicationService(properties(keyPrefix = prefix), port)

                    service.issueUploadUrl(input())

                    port.keys.single() shouldMatch Regex("""^dev/images/scan/.+""")
                    port.keys.single().contains("//") shouldBe false
                }
            }
        }

        `when`("같은 회원이 연속 두 번 발급하면") {
            then("객체 키가 서로 다르다") {
                val port = RecordingPort()
                val service = ImageUploadApplicationService(properties(), port)
                service.issueUploadUrl(input())
                service.issueUploadUrl(input())
                (port.keys[0] == port.keys[1]) shouldBe false
            }
        }
    }
})
