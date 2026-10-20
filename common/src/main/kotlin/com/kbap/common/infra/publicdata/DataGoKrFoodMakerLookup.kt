package com.kbap.common.infra.publicdata

import com.kbap.common.port.publicdata.FoodMakerLookup
import tools.jackson.databind.JsonNode

class DataGoKrFoodMakerLookup(
    private val client: DataGoKrClient,
) : FoodMakerLookup {
    override fun getMakerOrNull(reportNo: String): String? {
        val json = client.getJson(
            OPERATION, PATH, KEY_PARAM,
            mapOf("type" to "json", "pageNo" to 1, "numOfRows" to PAGE_SIZE, "ITEM_REPORT_NO" to reportNo),
            SUCCESS,
        )
        val makers: List<String> = json.path("body").path("items").values().map { item: JsonNode -> item.path("MAKER_NM").asString().trim() }
        return makers.firstOrNull { maker: String -> maker.isNotEmpty() && maker != NO_VALUE }
    }

    private companion object {
        const val OPERATION = "식품영양성분DB"
        const val PATH = "/1471000/FoodNtrCpntDbInfo03/getFoodNtrCpntDbInq03"
        const val KEY_PARAM = "serviceKey"
        const val PAGE_SIZE = 10
        const val NO_VALUE = "None"
        const val SUCCESS = "00"
    }
}
