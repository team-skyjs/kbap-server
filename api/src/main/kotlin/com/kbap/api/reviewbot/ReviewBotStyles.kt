package com.kbap.api.reviewbot

import com.kbap.common.port.llm.ReviewDraftRequest
import kotlin.random.Random

object ReviewBotStyles {
    val ALL: List<String> = listOf(
        "Open with a one-word reaction, then give one short reason.",
        "Mention in a few words who you shared it with.",
        "Compare it briefly to a similar dish from your home country.",
        "Say what you want to order next time or what you would add.",
        "Include one small, honest complaint about the taste or texture alongside the praise.",
        "Write like a quick casual chat message, lowercase where the language has case, trailing off with an ellipsis.",
        "Phrase it as a rhetorical question about the dish.",
        "React as someone trying this dish for the very first time.",
        "Say in one line what kind of eater would enjoy it, like spice lovers or noodle fans.",
        "Comment on how filling the portion felt.",
    )

    fun pick(random: Random): String = ALL.random(random)

    fun sentences(random: Random): Int = random.nextInt(1, 3)

    fun restyle(request: ReviewDraftRequest, random: Random): ReviewDraftRequest =
        request.copy(style = pick(random), sentences = sentences(random))
}
