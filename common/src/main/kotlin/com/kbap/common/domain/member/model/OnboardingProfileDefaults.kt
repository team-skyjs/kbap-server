package com.kbap.common.domain.member.model

object OnboardingProfileDefaults {
    val PROFILE_IMAGE_PATHS: List<String> = listOf(
        "images/webp/default_profile/avatar-amber.png",
        "images/webp/default_profile/avatar-navy.png",
        "images/webp/default_profile/avatar-olive.png",
        "images/webp/default_profile/avatar-orange.png",
        "images/webp/default_profile/avatar-plum.png",
        "images/webp/default_profile/avatar-teal.png",
    )

    val NICKNAME_POOL: List<String> = listOf(
        "Bibimbap", "Kimchi", "Tteokbokki", "Bulgogi", "Japchae",
        "Gimbap", "Mandu", "Samgyetang", "Galbi", "Naengmyeon",
        "Sundubu", "Doenjang", "Gochujang", "Pajeon", "Hotteok",
        "Bingsu", "Jjajangmyeon", "Jjamppong", "Dakgalbi", "Samgyeopsal",
        "Kalguksu", "Songpyeon", "Yukgaejang", "Bossam", "Jokbal",
        "HaemulTang", "Gamjatang", "Miyeokguk", "Omurice", "Dakjuk",
    )

    fun randomNickname(): String =
        "${NICKNAME_POOL.random()}_${(0..9999).random().toString().padStart(4, '0')}"

    fun randomProfileImagePath(): String = PROFILE_IMAGE_PATHS.random()
}
