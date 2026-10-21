package com.kbap.api.image

import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.port.storage.StorageObjectStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Duration

class BotNamespaceProtectionTest : BehaviorSpec({
    val paths = listOf(
        "prod/images/review-bot/key/1.webp",
        "dev/images/review-bot/key/1.webp",
        "prod/images/review-bot/invalid",
        "prod/images/review/../../images/review-bot/key/1.webp",
        "prod/images/./review-bot/key/1.webp",
        "prod/images/x/../review-bot/key/1.webp",
        "prod%2Fimages%2Freview-bot%2Fkey%2F1.webp",
        "prod%252Fimages%252Freview-bot%252Fkey%252F1.webp",
        "prod\\images\\review-bot\\key\\1.webp",
        "prod/images/REVIEW-BOT/key/1.webp",
    )
    given("봇 전용 사진 완료 신고") {
        paths.forEach { path ->
            `when`("보호 경로 $path 를 회원 또는 게스트가 신고하면") {
                then("소유 등록 조회와 저장소 및 할당량 접근 전에 거절한다") {
                    listOf(1L, null).forEach { memberId ->
                        val storage = mock(StorageObjectStore::class.java)
                        val repository = mock(UploadedImageJpaRepository::class.java)
                        val quota = mock(GuestUploadQuota::class.java)
                        val properties = ImageUploadProperties(setOf("image/jpeg"), 1024, Duration.ofMinutes(5), "", "prod", Duration.ofDays(1))
                        val service = ImageUploadService(storage, repository, quota, properties)
                        shouldThrow<BusinessException> {
                            service.completeUpload(memberId, "namespace-local", path, "image/jpeg", 512)
                        }
                        verifyNoInteractions(storage, repository, quota)
                    }
                }
            }
        }
    }
})
