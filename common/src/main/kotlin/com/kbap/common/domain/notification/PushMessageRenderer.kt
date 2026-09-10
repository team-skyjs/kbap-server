package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.stereotype.Service

@Service
class PushMessageRenderer {
    fun render(type: NotificationType, lang: LanguageCode, args: Map<String, String>, marketing: Boolean): PushContent {
        val template = PushTemplates.byType.getValue(type).getValue(lang)
        var title = fill(template.title, args)
        var body = fill(template.body, args)
        if (marketing) {
            title = MARKETING_PREFIX + title
            body = body + "\n" + PushTemplates.optOutNotice.getValue(lang)
        }
        return PushContent(title.take(TITLE_MAX_LENGTH), body.take(BODY_MAX_LENGTH))
    }

    private fun fill(template: String, args: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match -> args[match.groupValues[1]] ?: "" }

    companion object {
        private const val MARKETING_PREFIX = "(광고) "
        private const val TITLE_MAX_LENGTH = 200
        private const val BODY_MAX_LENGTH = 1000
        private val PLACEHOLDER = Regex("\\{(\\w+)}")
    }
}
