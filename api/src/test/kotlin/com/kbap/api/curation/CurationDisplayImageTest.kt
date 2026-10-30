package com.kbap.api.curation

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.curation.CurationAssetRepository
import com.kbap.common.domain.curation.CurationCollectionRepository
import com.kbap.common.domain.curation.CurationReleaseRepository
import com.kbap.common.domain.curation.model.CurationAsset
import com.kbap.common.domain.curation.model.CurationCollection
import com.kbap.common.domain.curation.model.CurationRelease
import com.kbap.common.port.storage.ImmutableObjectMetadata
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.CRC32
import javax.imageio.ImageIO

@IntegrationTest
class CurationDisplayImageTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired private lateinit var service: CurationAssetService
    @Autowired private lateinit var curationService: CurationService
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
            adminId = (admins.findByLoginId("curation-display-test") ?: admins.save(AdminAccount("curation-display-test", "test"))).id
            val root = releases.save(CurationRelease())
            CurationService.keys.forEach { collections.save(CurationCollection(root.id, it)) }
        }

        fun upload(bytes: ByteArray, contentType: String): Long {
            val reserved = transactions.reserve(adminId, "dev", "sights", 0, contentType, bytes.size.toLong())
            store.objects[reserved.stagingKey] = ImmutableObjectMetadata(contentType, bytes.size.toLong(), "etag-${reserved.assetId}")
            store.bytes[reserved.stagingKey] = bytes
            service.complete(adminId, "dev", "sights", reserved.assetId, contentType, bytes.size.toLong())
            return reserved.assetId
        }

        fun publishSightsWith(assetId: Long) {
            listOf("stations", "busan").forEach { key ->
                val row = collections.findByCollectionKey(key)!!
                val fixture = assets.save(
                    CurationAsset(
                        collectionId = row.id, issuedByAdminId = adminId, assetState = "READY", stagingKey = "curation/dev/staging/$key", finalKey = "curation/dev/final/$key",
                        contentType = "image/png", sizeBytes = 10, expiresAt = LocalDateTime.now().plusMinutes(5), completeDeadline = LocalDateTime.now().plusDays(1),
                        completedAt = LocalDateTime.now(), sourceFingerprint = "fixture",
                    ),
                )
                val saved = curationService.updateDraft(adminId, "dev", key, curationService.getAdminDetail(adminId, key).version, CurationFixtures.document(key, fixture.id))
                curationService.publish(adminId, "dev", key, saved.version)
            }
            val saved = curationService.updateDraft(adminId, "dev", "sights", curationService.getAdminDetail(adminId, "sights").version, CurationFixtures.document("sights", assetId))
            curationService.publish(adminId, "dev", "sights", saved.version)
        }

        given("큐레이션 사진 업로드 확정 — 표시용 변형(KB-930)") {
            `when`("폭 3000 JPEG 를 확정하면") {
                then("원본 옆에 폭 1080 WebP 변형을 immutable 로 저장하고 display_key 에 남긴다") {
                    val original = photo(3000, 2000, "jpeg")

                    val asset = assets.findById(upload(original, "image/jpeg")).get()

                    val displayKey = asset.displayKey.shouldNotBeNull()
                    displayKey shouldBe "${asset.finalKey}-w1080.webp"
                    store.immutablePuts[displayKey] shouldBe "image/webp"
                    val variant = store.bytes.getValue(displayKey)
                    variant.size shouldBeLessThan original.size
                    ImageIO.read(ByteArrayInputStream(variant)).let {
                        it.width shouldBe 1080
                        it.height shouldBe 720
                    }
                }
            }

            `when`("변환이 실패하면(읽을 수 없는 이미지)") {
                then("업로드는 READY 로 확정되고 변형 없이 원본만 남는다") {
                    val asset = assets.findById(upload(byteArrayOf(1, 2, 3, 4, 5), "image/png")).get()

                    asset.assetState shouldBe "READY"
                    asset.finalKey.shouldNotBeNull()
                    asset.displayKey.shouldBeNull()
                    store.immutablePuts.size shouldBe 0
                }
            }
        }

        given("공개 큐레이션 응답의 사진 URL(KB-930)") {
            `when`("변형이 있는 사진이면") {
                then("기존 URL 필드는 변형, originalImageUrl 은 원본이다") {
                    val assetId = upload(photo(2000, 1000, "jpeg"), "image/jpeg")
                    val asset = assets.findById(assetId).get()
                    publishSightsWith(assetId)

                    val detail = curationService.getDetail("sights", "en")

                    detail.coverImageUrl shouldEndWith asset.displayKey!!
                    detail.coverOriginalImageUrl shouldEndWith asset.finalKey!!
                    detail.items.first().let { entry ->
                        entry.imageUrl shouldEndWith asset.displayKey!!
                        entry.originalImageUrl shouldEndWith asset.finalKey!!
                        entry.images.first().url shouldEndWith asset.displayKey!!
                        entry.images.first().originalUrl shouldEndWith asset.finalKey!!
                    }
                }
            }

            `when`("변형이 없는 사진이면") {
                then("모든 URL 이 원본으로 폴백한다") {
                    val assetId = upload(byteArrayOf(9, 9, 9), "image/png")
                    val asset = assets.findById(assetId).get()
                    publishSightsWith(assetId)

                    val detail = curationService.getDetail("sights", "en")

                    detail.coverImageUrl shouldEndWith asset.finalKey!!
                    detail.coverOriginalImageUrl shouldBe detail.coverImageUrl
                    detail.items.first().imageUrl shouldBe detail.items.first().originalImageUrl
                }
            }
        }

        given("기존 자산 변형 백필(KB-930)") {
            `when`("dryRun 으로 부르면") {
                then("변형 없는 READY 자산 수만 세고 아무것도 바꾸지 않는다") {
                    val convertible = upload(photo(1500, 1500, "png"), "image/png")
                    val broken = upload(byteArrayOf(7, 7, 7), "image/png")
                    jdbc.update("UPDATE curation_asset SET display_key = NULL")
                    store.immutablePuts.clear()

                    val result = service.backfillDisplayVariants(adminId, "dev", dryRun = true, limit = 100)

                    result.candidates shouldBe 2
                    result.converted shouldBe 0
                    assets.findById(convertible).get().displayKey.shouldBeNull()
                    assets.findById(broken).get().displayKey.shouldBeNull()
                    store.immutablePuts.size shouldBe 0
                }
            }

            `when`("실행하면") {
                then("변환되는 자산만 변형을 채우고 실패는 원본 그대로 두며 전후 바이트를 돌려준다") {
                    val convertible = upload(photo(1500, 1500, "png"), "image/png")
                    val broken = upload(byteArrayOf(7, 7, 7), "image/png")
                    jdbc.update("UPDATE curation_asset SET display_key = NULL")

                    val result = service.backfillDisplayVariants(adminId, "dev", dryRun = false, limit = 100)

                    result.candidates shouldBe 2
                    result.converted shouldBe 1
                    result.failedAssetIds shouldBe listOf(broken)
                    result.originalBytes shouldBe store.objects.getValue(assets.findById(convertible).get().finalKey!!).sizeBytes
                    (result.displayBytes in 1 until result.originalBytes) shouldBe true
                    assets.findById(convertible).get().displayKey.shouldNotBeNull()
                    assets.findById(broken).get().displayKey.shouldBeNull()

                    service.backfillDisplayVariants(adminId, "dev", dryRun = true, limit = 100).candidates shouldBe 1
                }
            }
        }

        given("표시용 변형 변환기") {
            val converter = CurationDisplayImageConverter()

            `when`("EXIF 방향이 6(시계 방향 90도)인 JPEG 면") {
                then("방향을 적용해 세로로 세운다 — 왼쪽(빨강)이 위로 간다") {
                    val variant = converter.toDisplayWebp(withExifOrientation(halves(400, 200), 6)).shouldNotBeNull()

                    ImageIO.read(ByteArrayInputStream(variant)).let {
                        it.width shouldBe 200
                        it.height shouldBe 400
                        Color(it.getRGB(100, 50)).let { top -> (top.red > top.blue + 100) shouldBe true }
                        Color(it.getRGB(100, 350)).let { bottom -> (bottom.blue > bottom.red + 100) shouldBe true }
                    }
                }
            }

            `when`("폭이 1080 이하면") {
                then("크기는 그대로 두고 WebP 로만 바꾼다") {
                    ImageIO.read(ByteArrayInputStream(converter.toDisplayWebp(photo(800, 600, "png")).shouldNotBeNull())).width shouldBe 800
                }
            }

            `when`("헤더상 픽셀 수가 상한을 넘으면") {
                then("디코드하지 않고 변형을 만들지 않는다 — 상한 안이면 만든다") {
                    val twoMegapixels = photo(2000, 1000, "png")
                    CurationDisplayImageConverter(maxPixels = 1_000_000).toDisplayWebp(twoMegapixels).shouldBeNull()
                    converter.toDisplayWebp(twoMegapixels).shouldNotBeNull()
                    converter.toDisplayWebp(pngHeaderOnly(8000, 8000)).shouldBeNull()
                }
            }
        }
    }

    private fun photo(width: Int, height: Int, format: String): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        for (x in 0 until width step 50) {
            g.color = Color((x * 7) % 256, (x * 3) % 256, (x * 11) % 256)
            g.fillRect(x, 0, 50, height)
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private fun halves(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width / 2, height)
        g.color = Color.BLUE
        g.fillRect(width / 2, 0, width - width / 2, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        fun putInt(at: Int, value: Int) { for (k in 0..3) png[at + k] = (value shr (24 - 8 * k)).toByte() }
        putInt(16, width)
        putInt(20, height)
        val crc = CRC32().apply { update(png, 12, 17) }.value.toInt()
        putInt(29, crc)
        return png
    }
}
