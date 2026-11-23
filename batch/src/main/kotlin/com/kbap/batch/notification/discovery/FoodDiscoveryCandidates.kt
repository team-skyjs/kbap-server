package com.kbap.batch.notification.discovery

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.RiskLevel
import kotlin.random.Random

/** 후보 풀 = READY 이고 재료 평가가 끝난 음식. 평가 전 음식은 안전 판정이 불가능하다. */
class FoodDiscoveryCandidates(foods: Collection<Food>) {
    val foods: List<Food> = foods.filter { it.isReady() && it.ingredients != null }

    /**
     * 회원 한 명에게 보낼 음식 하나. 조건은 셋뿐이다:
     * 1) 그 회원의 회피 재료로 봤을 때 앱과 같은 기준으로 SAFE
     * 2) 그 회원의 모든 기기 언어에 설명이 있다 (기기마다 다른 음식을 보내지 않기 위해)
     * 3) 최근 보냈거나 저장한 음식이 아니다
     * 남은 것 중 균등 랜덤. 없으면 null.
     */
    fun pick(avoidedCodes: Set<String>, langs: Set<LanguageCode>, excludedFoodIds: Set<Long>, random: Random): Food? =
        foods
            .filter { it.overallRisk(avoidedCodes) == RiskLevel.SAFE } // ponytail: 회원마다 풀 전체 판정. 풀이 수천 행이 되면 회피 집합별 메모
            .filter { food -> langs.all { description(food, it) != null } }
            .filter { it.id !in excludedFoodIds }
            .randomOrNull(random)

    companion object {
        /** ko 는 원문, 그 외는 번역. 비어 있으면 null — 빈 설명·placeholder 를 광고로 보내지 않는다. */
        fun description(food: Food, lang: LanguageCode): String? =
            (if (lang == LanguageCode.KO) food.description else food.descriptionTranslations[lang.code])?.takeIf { it.isNotBlank() }
    }
}
