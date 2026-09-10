package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.stereotype.Service

@Service
class PushMessageRenderer {
    fun render(type: NotificationType, lang: LanguageCode, args: Map<String, String>): PushContent {
        val template = PushTemplates.byType.getValue(type).getValue(lang)
        val title = fill(template.title, args)
        val body = fill(template.body, args)
        if (!type.marketing) return PushContent(title.take(TITLE_MAX_LENGTH), body.take(BODY_MAX_LENGTH))

        val optOut = "\n" + PushTemplates.optOutNotice.getValue(lang)
        return PushContent(
            (MARKETING_PREFIX + title).take(TITLE_MAX_LENGTH),
            body.take(BODY_MAX_LENGTH - optOut.length) + optOut,
        )
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
