package com.kbap.common.infra.publicdata

import com.kbap.common.port.publicdata.HaccpProduct
import com.kbap.common.port.publicdata.HaccpProductPage
import com.kbap.common.port.publicdata.HaccpProductSource
import tools.jackson.databind.JsonNode

class DataGoKrHaccpProductSource(
    private val client: DataGoKrClient,
) : HaccpProductSource {
    override fun getPage(foodType: String, pageNo: Int, pageSize: Int): HaccpProductPage {
        val json = client.getJson(
            OPERATION, PATH, KEY_PARAM,
            mapOf("prdkind" to foodType, "returnType" to "json", "pageNo" to pageNo, "numOfRows" to pageSize),
            SUCCESS,
        )
        val body = json.path("body")
        val items: List<HaccpProduct> = body.path("items").values().mapNotNull { wrapper: JsonNode ->
            wrapper.path("item").takeIf { it.isObject }?.let(::toProduct)
        }
        return HaccpProductPage(items, body.path("totalCount").asString().toIntOrNull() ?: 0)
    }

    private fun toProduct(item: JsonNode): HaccpProduct? {
        val reportNo = item.text("prdlstReportNo") ?: return null
        val name = item.text("prdlstNm") ?: return null
        return HaccpProduct(
            reportNo = reportNo,
            productName = name,
            productGb = item.text("productGb"),
            foodType = item.text("prdkind"),
            foodTypeState = item.text("prdkindstate"),
            manufacturer = item.text("manufacture"),
            seller = item.text("seller"),
            barcode = item.text("barcode"),
            capacity = item.text("capacity"),
            rawMaterials = item.text("rawmtrl"),
            allergens = item.text("allergy"),
            nutrients = item.text("nutrient"),
            imageUrl = item.text("imgurl1"),
            metaImageUrl = item.text("imgurl2"),
        )
    }

    private fun JsonNode.text(field: String): String? = path(field).asString().trim().takeIf { it.isNotEmpty() }

    private companion object {
        const val OPERATION = "HACCP 제품정보"
        const val PATH = "/B553748/CertImgListServiceV3/getCertImgListServiceV3"
        const val KEY_PARAM = "ServiceKey"
        const val SUCCESS = "OK"
    }
}
