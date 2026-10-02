package com.kbap.api.review

import com.kbap.common.domain.LanguageCode
import org.springframework.stereotype.Component

@Component
class ReviewLanguageDetector {
    fun detect(content: String?): LanguageCode? = null
}
