package com.kbap.common.domain.processedfood.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "processed_food_product",
    uniqueConstraints = [UniqueConstraint(name = "uq_processed_food_product_report_no", columnNames = ["report_no"])],
)
class ProcessedFoodProduct(
    @Column(name = "report_no", nullable = false, length = 20)
    val reportNo: String = "",

    @Column(name = "product_name", nullable = false, length = 200)
    var productName: String = "",

    @Column(name = "normalized_name", nullable = false, length = 200)
    var normalizedName: String = "",

    @Column(name = "product_gb", length = 20)
    var productGb: String? = null,

    @Column(name = "food_type", length = 200)
    var foodType: String? = null,

    @Column(name = "food_type_state", length = 200)
    var foodTypeState: String? = null,

    @Column(name = "manufacturer", length = 300)
    var manufacturer: String? = null,

    @Column(name = "seller", length = 300)
    var seller: String? = null,

    @Column(name = "maker_supplement", length = 200)
    var makerSupplement: String? = null,

    @Column(name = "maker_checked_at")
    var makerCheckedAt: LocalDateTime? = null,

    @Column(name = "barcode", length = 50)
    var barcode: String? = null,

    @Column(name = "capacity", length = 300)
    var capacity: String? = null,

    @Column(name = "raw_materials", columnDefinition = "text")
    var rawMaterials: String? = null,

    @Column(name = "allergens", length = 1000)
    var allergens: String? = null,

    @Column(name = "nutrients", columnDefinition = "text")
    var nutrients: String? = null,

    @Column(name = "image_url", length = 500)
    var imageUrl: String? = null,

    @Column(name = "meta_image_url", length = 500)
    var metaImageUrl: String? = null,

    @Column(name = "source", nullable = false, length = 30)
    val source: String = SOURCE_HACCP,

    @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    var contentHash: String = "",

    @Column(name = "collected_at", nullable = false)
    val collectedAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: LocalDateTime = LocalDateTime.MIN,
) : BaseEntity() {
    fun refresh(snapshot: ProcessedFoodSnapshot, seenAt: LocalDateTime): Boolean {
        require(snapshot.reportNo == reportNo) { "품목보고번호가 다른 스냅샷으로 갱신할 수 없습니다" }
        lastSeenAt = seenAt
        val hash = snapshot.contentHash()
        if (hash == contentHash) return false
        copyFrom(snapshot)
        contentHash = hash
        return true
    }

    fun needsMakerLookup(): Boolean =
        makerCheckedAt == null && ProcessedFoodSnapshot.isUnknownMaker(manufacturer) && ProcessedFoodSnapshot.isUnknownMaker(seller)

    fun supplementMaker(maker: String?, checkedAt: LocalDateTime) {
        makerSupplement = maker?.trim()?.takeIf { it.isNotEmpty() }?.take(MAKER_SUPPLEMENT_LENGTH)
        makerCheckedAt = checkedAt
    }

    private fun copyFrom(snapshot: ProcessedFoodSnapshot) {
        productName = snapshot.productName
        normalizedName = snapshot.normalizedName()
        productGb = snapshot.productGb
        foodType = snapshot.foodType
        foodTypeState = snapshot.foodTypeState
        manufacturer = snapshot.manufacturer
        seller = snapshot.seller
        barcode = snapshot.barcode
        capacity = snapshot.capacity
        rawMaterials = snapshot.rawMaterials
        allergens = snapshot.allergens
        nutrients = snapshot.nutrients
        imageUrl = snapshot.imageUrl
        metaImageUrl = snapshot.metaImageUrl
    }

    companion object {
        const val SOURCE_HACCP = "HACCP"
        private const val MAKER_SUPPLEMENT_LENGTH = 200

        fun collect(snapshot: ProcessedFoodSnapshot, collectedAt: LocalDateTime): ProcessedFoodProduct =
            ProcessedFoodProduct(
                reportNo = snapshot.reportNo,
                contentHash = snapshot.contentHash(),
                collectedAt = collectedAt,
                lastSeenAt = collectedAt,
            ).apply { copyFrom(snapshot) }
    }
}
