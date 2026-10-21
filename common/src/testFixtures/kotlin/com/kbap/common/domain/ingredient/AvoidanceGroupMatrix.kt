package com.kbap.common.domain.ingredient

import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.ingredient.model.IngredientCode.*

data class AvoidanceGroupMember(val group: IngredientCode, val member: IngredientCode, val labelTerm: String)

object AvoidanceGroupMatrix {
    val rows: List<AvoidanceGroupMember> =
        listOf(
            NUTS to listOf(
                WALNUT to "호두", PINE_NUT to "잣", ALMOND to "아몬드", CASHEW to "캐슈넛", PISTACHIO to "피스타치오",
                HAZELNUT to "헤이즐넛", MACADAMIA to "마카다미아", PECAN to "피칸", BRAZIL_NUT to "브라질너트",
                CHESTNUT to "밤", PEANUT to "땅콩",
            ),
            CRUSTACEAN to listOf(
                SHRIMP to "새우", SALTED_SHRIMP to "새우젓", CRAB to "꽃게", CRAYFISH to "가재", LOBSTER to "랍스터",
            ),
            MOLLUSK to listOf(
                SQUID to "오징어", OCTOPUS to "문어", CLAM to "조개", SHORT_NECK_CLAM to "바지락", OYSTER to "굴",
                OYSTER_SAUCE to "굴소스", ABALONE to "전복", MUSSEL to "홍합", SCALLOP to "가리비",
            ),
            MEAT to listOf(
                BEEF to "소고기", TALLOW to "우지", PORK to "돼지고기", LARD to "라드", POULTRY to "가금육",
                CHICKEN to "닭고기", LAMB to "양고기", MEAT_BROTH to "사골", GELATIN to "젤라틴",
            ),
        ).flatMap { (group, members) -> members.map { (member, term) -> AvoidanceGroupMember(group, member, term) } }
}
