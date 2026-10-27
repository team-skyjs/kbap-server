package com.kbap.common.port.publicdata

interface HaccpProductSource {
    fun getPage(foodType: String?, pageNo: Int, pageSize: Int): HaccpProductPage
}

data class HaccpProductPage(
    val items: List<HaccpProduct>,
    val totalCount: Int,
)

data class HaccpProduct(
    val reportNo: String,
    val productName: String,
    val productGb: String?,
    val foodType: String?,
    val foodTypeState: String?,
    val manufacturer: String?,
    val seller: String?,
    val barcode: String?,
    val capacity: String?,
    val rawMaterials: String?,
    val allergens: String?,
    val nutrients: String?,
    val imageUrl: String?,
    val metaImageUrl: String?,
)
