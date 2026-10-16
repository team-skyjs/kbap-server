package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.port.storage.StorageObjectStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.PlatformTransactionManager

class UploadedImageCleanupCursorTest : BehaviorSpec({
    given("정리 커서 저장소 장애") {
        `when`("Redis 접근이 실패하면") {
            then("대상 조회와 저장소 삭제를 시작하지 않는다") {
                val redis = mock(StringRedisTemplate::class.java)
                val images = mock(UploadedImageJpaRepository::class.java)
                val storage = mock(StorageObjectStore::class.java)
                `when`(redis.opsForValue()).thenThrow(IllegalStateException("연결 실패"))
                val service = UploadedImageCleanupService(
                    images, storage, 7, false, 2, 3,
                    mock(PlatformTransactionManager::class.java),
                    UploadCleanupMetrics(SimpleMeterRegistry()), redis,
                )
                shouldThrow<IllegalStateException> { service.cleanup() }
                verifyNoInteractions(images, storage)
            }
        }
    }
})
