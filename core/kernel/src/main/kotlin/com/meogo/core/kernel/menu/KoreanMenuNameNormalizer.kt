package com.meogo.core.kernel.menu

import java.text.Normalizer

object KoreanMenuNameNormalizer {
    fun matchKey(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .filter { it in '가'..'힣' }
}
