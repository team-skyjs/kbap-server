package com.kbap.api.image

import com.kbap.common.domain.image.model.UploadPurpose
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.util.UUID

class IssuedUploadKeyTest : BehaviorSpec({

    val contentTypes = listOf("image/jpeg", "image/png", "image/webp")
    val uuid = UUID.randomUUID().toString()

    given("발급 키 왕복") {
        `when`("모든 용도 × 허용 형식으로 발급한 키를 같은 소유자로 해석하면") {
            then("전부 그 용도로 읽힌다 — 발급 형식이 바뀌면 완료 신고가 전량 400 이 되는 사고를 막는다") {
                UploadPurpose.entries.forEach { purpose ->
                    contentTypes.forEach { contentType ->
                        val member = PresignedUploadService.issuedKey("prod", purpose, "42", contentType)
                        PresignedUploadService.issuedPurposeOf(member, "prod", "42") shouldBe purpose
                        val guest = PresignedUploadService.issuedKey("", purpose, PresignedUploadService.guestOwnerKey("install-0001"), contentType)
                        PresignedUploadService.issuedPurposeOf(guest, "", PresignedUploadService.guestOwnerKey("install-0001")) shouldBe purpose
                    }
                }
            }
        }
        `when`("게스트 소유자 키를 만들면") {
            then("g + sha256(설치 id)[:12] 이고 설치 id 가 다르면 다르다 — 게스트끼리 키를 못 넘본다") {
                PresignedUploadService.guestOwnerKey("install-0001") shouldMatch Regex("^g[0-9a-f]{12}$")
                PresignedUploadService.guestOwnerKey("install-0001") shouldBe PresignedUploadService.guestOwnerKey("install-0001")
                (PresignedUploadService.guestOwnerKey("install-0001") == PresignedUploadService.guestOwnerKey("install-0002")) shouldBe false
            }
        }
    }

    given("발급 키 해석의 음성 케이스") {
        val base = "local/images/review/2026/10"
        `when`("소유자·uuid·경로·확장자가 조금씩 어긋나면") {
            then("전부 null 이다") {
                PresignedUploadService.issuedPurposeOf("$base/100_$uuid.jpg", "local", "10") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/10_$uuid.jpg", "local", "100") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/012_$uuid.jpg", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/12_${uuid.uppercase()}.jpg", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("local/images/review/2026/../10/12_$uuid.jpg", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("local//images/review/2026/10/12_$uuid.jpg", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("local/local/images/review/2026/10/12_$uuid.jpg", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/12_$uuid.jpg.exe", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/12_$uuid.jpg/../x", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/12_$uuid.jpg", "", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/12_$uuid.jpg", "prod", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("local/images/unknown/2026/10/12_$uuid.jpg", "local", "12") shouldBe null
                PresignedUploadService.issuedPurposeOf("$base/g0123456789ab_$uuid.jpg", "local", "12") shouldBe null
            }
        }
        `when`("정확히 맞으면") {
            then("용도를 돌려준다") {
                PresignedUploadService.issuedPurposeOf("$base/12_$uuid.jpg", "local", "12") shouldBe UploadPurpose.REVIEW
                PresignedUploadService.issuedPurposeOf("local/images/feedback/2026/10/g0123456789ab_$uuid.webp", "local", "g0123456789ab") shouldBe UploadPurpose.FEEDBACK
            }
        }
    }
})
