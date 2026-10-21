package com.kbap.api.reviewbot

import com.kbap.api.image.ImageUploadProperties
import com.kbap.common.core.error.BusinessException
import com.kbap.common.port.storage.StorageObjectStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Duration

class ReviewBotPhotoGateTest : BehaviorSpec({
    given("관리자 사진 sync 쓰기 gate") {
        `when`("gate가 꺼져 있으면") {
            then("계정과 등록행 및 저장소에 접근하지 않고 거절한다") {
                val catalog = mock(ReviewBotPhotoCatalog::class.java)
                val storage = mock(StorageObjectStore::class.java)
                val accounts = mock(ReviewBotAccountService::class.java)
                val properties = ImageUploadProperties(setOf("image/webp"), 1024, Duration.ofMinutes(5), "", "prod", Duration.ofDays(1))
                val service = ReviewBotPhotoService(catalog, storage, accounts, properties, false)
                shouldThrow<BusinessException> { service.sync() }
                verifyNoInteractions(catalog, storage, accounts)
            }
        }
    }
})
