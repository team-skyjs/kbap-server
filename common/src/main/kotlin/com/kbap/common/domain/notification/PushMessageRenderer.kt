package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.util.Locale
import kotlin.random.Random

@Service
class PushMessageRenderer(
    private val messageSource: MessageSource,
    private val pickVariant: (count: Int) -> Int = { Random.nextInt(it) },
) {
    fun render(type: NotificationType, lang: LanguageCode, args: Map<String, String>, slot: MealSlot? = null): PushContent {
        val template = template(type, lang.locale, slot)
        val title = fill(template.title, args)
        val body = fill(template.body, args)
        if (!type.marketing) return PushContent(title.take(TITLE_MAX_LENGTH), body.take(BODY_MAX_LENGTH))

        val optOut = "\n" + messageSource.getMessage(OPT_OUT_KEY, null, lang.locale)
        return PushContent(
            (MARKETING_PREFIX + title).take(TITLE_MAX_LENGTH),
            body.take(BODY_MAX_LENGTH - optOut.length) + optOut,
        )
    }

    private fun template(type: NotificationType, locale: Locale, slot: MealSlot?): PushContent {
        val typePrefix = "push.${type.name.lowercase()}"
        val slotPrefix = slot?.let { "$typePrefix.${it.name.lowercase()}" }
        val prefix = slotPrefix?.takeIf { variantCount(it, locale) > 0 } ?: typePrefix
        val variant = pickVariant(variantCount(prefix, locale)) + 1
        return PushContent(
            messageSource.getMessage("$prefix.$variant.title", null, locale),
            messageSource.getMessage("$prefix.$variant.body", null, locale),
        )
    }

    private fun variantCount(prefix: String, locale: Locale): Int =
        generateSequence(1) { it + 1 }
            .takeWhile { messageSource.getMessage("$prefix.$it.title", null, null, locale) != null }
            .count()

    private fun fill(template: String, args: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match -> args[match.groupValues[1]] ?: "" }

    companion object {
        private const val OPT_OUT_KEY = "push.opt-out"
        private const val MARKETING_PREFIX = "(광고) "
        private const val TITLE_MAX_LENGTH = 200
        private const val BODY_MAX_LENGTH = 1000
        private val PLACEHOLDER = Regex("\\{(\\w+)}")
    }
}
