package com.kbap.api.curation

import com.kbap.api.IntegrationTest
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.curation.*
import com.kbap.common.domain.curation.model.*
import com.kbap.common.port.storage.ImmutableObjectMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class CurationAssetTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired private lateinit var service: CurationAssetService
    @Autowired private lateinit var transactions: CurationAssetTransactions
    @Autowired private lateinit var store: FakeImmutableObjectStore
    @Autowired private lateinit var assets: CurationAssetRepository
    @Autowired private lateinit var collections: CurationCollectionRepository
    @Autowired private lateinit var releases: CurationReleaseRepository
    @Autowired private lateinit var admins: AdminAccountJpaRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    private var adminId = 0L
    init {
        beforeEach {
            store.reset()
            jdbc.update("DELETE FROM curation_asset")
            jdbc.update("DELETE FROM curation_collection")
            jdbc.update("DELETE FROM curation_release")
            adminId = (admins.findByLoginId("curation-upload-test") ?: admins.save(AdminAccount("curation-upload-test", "test"))).id
            val root = releases.save(CurationRelease())
            CurationService.keys.forEach { collections.save(CurationCollection(root.id, it)) }
        }
        given("관리자 큐레이션 업로드") {
            `when`("예약과 완료를 반복하면") {
                then("콘텐츠 버전은 그대로이고 최종 경로는 한번만 확정된다") {
                    val upload = service.createUpload(adminId, "dev", "sights", 0, "image/png", 100)
                    upload.requiredHeaders.keys.any { it.equals("Content-Length", true) } shouldBe false
                    collections.findByCollectionKey("sights")!!.version shouldBe 0
                    val asset = assets.findById(upload.assetId).get()
                    store.objects[asset.stagingKey] = ImmutableObjectMetadata("image/png", 100, "etag1")
                    val complete = service.complete(adminId, "dev", "sights", asset.id, "image/png", 100)
                    store.objects[asset.stagingKey] = ImmutableObjectMetadata("image/png", 100, "changed")
                    service.complete(adminId, "dev", "sights", asset.id, "image/png", 100) shouldBe complete
                    store.copyCount shouldBe 1
                    assets.findById(asset.id).get().finalKey!!.startsWith("curation/dev/final/") shouldBe true
                }
            }
            `when`("HEAD 뒤 원본이 바뀌면") {
                then("조건부 복사가 실패하고 READY를 채택하지 않는다") {
                    val upload = transactions.reserve(adminId, "dev", "sights", 0, "image/png", 100)
                    store.objects[upload.stagingKey] = ImmutableObjectMetadata("image/png", 100, "etag1")
                    store.beforeCopy = { store.objects[upload.stagingKey] = ImmutableObjectMetadata("image/png", 100, "etag2") }
                    shouldThrow<BusinessException> { service.complete(adminId, "dev", "sights", upload.assetId, "image/png", 100) }.errorCode.code shouldBe "CURATION-006"
                    assets.findById(upload.assetId).get().assetState shouldBe "ISSUED"
                }
            }
            `when`("다른 발급자나 모음이나 환경으로 확정하면") {
                then("복사 전에 거절한다") {
                    val upload = transactions.reserve(adminId, "dev", "sights", 0, "image/png", 100)
                    val other = (admins.findByLoginId("curation-other") ?: admins.save(AdminAccount("curation-other", "test"))).id
                    shouldThrow<BusinessException> { service.complete(other, "dev", "sights", upload.assetId, "image/png", 100) }.errorCode.code shouldBe "CURATION-005"
                    shouldThrow<BusinessException> { service.complete(adminId, "dev", "stations", upload.assetId, "image/png", 100) }.errorCode.code shouldBe "CURATION-005"
                    shouldThrow<BusinessException> { service.complete(adminId, "prod", "sights", upload.assetId, "image/png", 100) }.errorCode.code shouldBe "CURATION-004"
                    store.copyCount shouldBe 0
                }
            }
            `when`("복사 후 DB 채택 전에 기한이 끝나면") {
                then("확정을 거절하고 복사본을 게시 자산으로 노출하지 않는다") {
                    val upload = transactions.reserve(adminId, "dev", "sights", 0, "image/png", 100)
                    store.objects[upload.stagingKey] = ImmutableObjectMetadata("image/png", 100, "etag1")
                    store.beforeCopy = { jdbc.update("UPDATE curation_asset SET expires_at=DATE_SUB(NOW(), INTERVAL 2 DAY), complete_deadline=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id=?", upload.assetId) }
                    shouldThrow<BusinessException> { service.complete(adminId, "dev", "sights", upload.assetId, "image/png", 100) }.errorCode.code shouldBe "CURATION-005"
                    assets.findById(upload.assetId).get().finalKey shouldBe null
                    store.copyCount shouldBe 1
                }
            }
            `when`("두 complete가 동시에 같은 원본을 복사하면") {
                then("동일 최종 자산을 반환하고 패자 복사본이 승자를 지우지 않는다") {
                    val upload = transactions.reserve(adminId, "dev", "sights", 0, "image/png", 100)
                    store.objects[upload.stagingKey] = ImmutableObjectMetadata("image/png", 100, "etag1")
                    val copied = CountDownLatch(2)
                    store.beforeCopy = { copied.countDown(); check(copied.await(10, TimeUnit.SECONDS)) }
                    val pool = Executors.newFixedThreadPool(2)
                    try {
                        val results = (1..2).map { pool.submit(Callable { service.complete(adminId, "dev", "sights", upload.assetId, "image/png", 100) }) }.map { it.get() }
                        results[0] shouldBe results[1]
                        val finalKey = assets.findById(upload.assetId).get().finalKey!!
                        store.objects.containsKey(finalKey) shouldBe true
                        store.objects.keys.count { it.contains("/final/") } shouldBe 2
                    } finally { pool.shutdownNow() }
                }
            }
            `when`("두 복사 결과가 연속 채택을 시도하면") {
                then("두번째 요청은 최초 채택 final을 반환한다") {
                    val upload = transactions.reserve(adminId, "dev", "sights", 0, "image/png", 100)
                    val first = transactions.complete(adminId, "dev", "sights", upload.assetId, "image/png", 100, "curation/dev/final/a", "etag")
                    val second = transactions.complete(adminId, "dev", "sights", upload.assetId, "image/png", 100, "curation/dev/final/b", "etag")
                    first.finalKey shouldBe second.finalKey
                }
            }
        }
    }
}
