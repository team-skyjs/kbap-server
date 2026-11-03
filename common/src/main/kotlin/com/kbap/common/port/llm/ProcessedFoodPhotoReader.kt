package com.kbap.common.port.llm

interface ProcessedFoodPhotoReader {
    fun read(imagePath: String): ProcessedFoodPhotoReading
}

enum class ProcessedFoodPhotoKind { MENU, PACKAGE_FRONT, INGREDIENT_LABEL, OTHER }

enum class ProcessedFoodPhotoReason { BLURRY, TOO_DARK, GLARE, CROPPED, NOT_FOOD, UNKNOWN }

data class ProcessedFoodPhotoReading(
    val kind: ProcessedFoodPhotoKind,
    val confidence: Double,
    val reason: ProcessedFoodPhotoReason? = null,
    val productName: String? = null,
    val maker: String? = null,
    val reportNo: String? = null,
    val labelText: String? = null,
    val warnings: List<String> = emptyList(),
)
