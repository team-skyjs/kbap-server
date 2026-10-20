package com.kbap.common.domain.processedfood.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Duration
import java.time.LocalDateTime

@Entity
@Table(
    name = "processed_food_product",
    uniqueConstraints = [UniqueConstraint(name = "uq_processed_food_product_report_no", columnNames = ["report_no"])],
)
class ProcessedFoodProduct(
    @Column(name = "report_no", nullable = false, length = REPORT_NO_LENGTH)
    val reportNo: String = "",

    @Column(name = "product_name", nullable = false, length = NAME_LENGTH)
    var productName: String = "",

    @Column(name = "normalized_name", nullable = false, length = NAME_LENGTH)
    var normalizedName: String = "",

    @Column(name = "product_gb", length = PRODUCT_GB_LENGTH)
    var productGb: String? = null,

    @Column(name = "food_type", length = FOOD_TYPE_LENGTH)
    var foodType: String? = null,

    @Column(name = "food_type_state", length = FOOD_TYPE_LENGTH)
    var foodTypeState: String? = null,

    @Column(name = "manufacturer", length = MAKER_LENGTH)
    var manufacturer: String? = null,

    @Column(name = "seller", length = MAKER_LENGTH)
    var seller: String? = null,

    @Column(name = "maker_supplement", length = 200)
    var makerSupplement: String? = null,

    @Column(name = "maker_checked_at")
    var makerCheckedAt: LocalDateTime? = null,

    @Column(name = "barcode", length = BARCODE_LENGTH)
    var barcode: String? = null,

    @Column(name = "capacity", length = CAPACITY_LENGTH)
    var capacity: String? = null,

    @Column(name = "raw_materials", columnDefinition = "text")
    var rawMaterials: String? = null,

    @Column(name = "allergens", length = ALLERGENS_LENGTH)
    var allergens: String? = null,

    @Column(name = "nutrients", columnDefinition = "text")
    var nutrients: String? = null,

    @Column(name = "image_url", length = URL_LENGTH)
    var imageUrl: String? = null,

    @Column(name = "meta_image_url", length = URL_LENGTH)
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

    fun displayMaker(): String? =
        listOf(manufacturer, seller, makerSupplement).firstNotNullOfOrNull(::companyNameOf)

    fun isDiscontinued(lastCompletedMirrorAt: LocalDateTime?): Boolean =
        lastCompletedMirrorAt != null && lastSeenAt.isBefore(lastCompletedMirrorAt.minus(DISCONTINUED_AFTER))

    companion object {
        val DISCONTINUED_AFTER: Duration = Duration.ofDays(3)
        private val COMPANY_FORMS = Regex("""농업회사법인|주식회사|유한회사|㈜|\(주\)|\(유\)""")
        private val MAKER_CUT = Regex("""[_/:,(]""")
        private val TRAILING_NOISE = Regex("""본사$|(?<=[가-힣])[A-Z0-9]+$""")
        private val FIRST_WORD_STOP = Regex("""\d|공장$|^본사$""")
        private val ADDRESS_WORD = Regex("""\d|공장$|^본사$|(특별시|광역시|특별자치시|특별자치도|[시도군구])$|^(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)$""")

        fun companyNameOf(raw: String?): String? {
            if (raw == null || ProcessedFoodSnapshot.isUnknownMaker(raw)) return null
            val words = raw.replace(COMPANY_FORMS, " ").split(MAKER_CUT).first().trim().split(Regex("""\s+"""))
            val name = words.withIndex()
                .takeWhile { (index, word) -> word.isNotEmpty() && !(if (index == 0) FIRST_WORD_STOP else ADDRESS_WORD).containsMatchIn(word) }
                .joinToString(" ") { it.value }
            return name.replace(TRAILING_NOISE, "").takeIf { it.isNotEmpty() }
        }
        const val SOURCE_HACCP = "HACCP"
        const val REPORT_NO_LENGTH = 20
        const val NAME_LENGTH = 200
        const val PRODUCT_GB_LENGTH = 20
        const val FOOD_TYPE_LENGTH = 200
        const val MAKER_LENGTH = 300
        const val BARCODE_LENGTH = 50
        const val CAPACITY_LENGTH = 300
        const val ALLERGENS_LENGTH = 1000
        const val URL_LENGTH = 500
        private const val MAKER_SUPPLEMENT_LENGTH = 200
        private val HACCP_HTTP = Regex("""^http://(?=(www\.)?haccp\.or\.kr/)""")

        fun secureImageUrl(url: String?): String? =
            url?.let { HACCP_HTTP.replaceFirst(it, "https://") }

        fun collect(snapshot: ProcessedFoodSnapshot, collectedAt: LocalDateTime): ProcessedFoodProduct =
            ProcessedFoodProduct(
                reportNo = snapshot.reportNo,
                contentHash = snapshot.contentHash(),
                collectedAt = collectedAt,
                lastSeenAt = collectedAt,
            ).apply { copyFrom(snapshot) }
    }
}
