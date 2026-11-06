package com.kbap.common.port.llm

interface MenuPhotoClassifier {
    fun classify(imagePath: String): MenuPhotoVerdict
}

sealed interface MenuPhotoVerdict {
    data class Menu(val probability: Double) : MenuPhotoVerdict

    data object NotMenu : MenuPhotoVerdict
}

class MenuPhotoClassificationFailed(val reason: String, cause: Throwable? = null) : RuntimeException("menu photo classification failed: $reason", cause)
