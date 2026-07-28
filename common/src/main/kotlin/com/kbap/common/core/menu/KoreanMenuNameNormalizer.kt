package com.kbap.common.core.menu

import java.text.Normalizer

object KoreanMenuNameNormalizer {
    const val MAX_MENU_NAME_LENGTH = 255

    fun matchKey(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .filter { it in '가'..'힣' }
}
