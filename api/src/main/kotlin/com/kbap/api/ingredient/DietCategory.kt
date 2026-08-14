package com.kbap.api.ingredient

import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.ingredient.model.IngredientCode.*

private val dairyIngredients = setOf(MILK, DAIRY, GOAT_MILK, BUTTER, GHEE, CHEESE)
private val animalAdditives = setOf(GELATIN, RENNET, CARMINE)
private val shellfishAndSeafood = IngredientCode.entries.subList(SHRIMP.ordinal, SEAFOOD.ordinal + 1).toSet()
private val animalFlesh = IngredientCode.entries.subList(SHRIMP.ordinal, POULTRY.ordinal + 1).toSet()
private val landMeat = setOf(BEEF, PORK, LARD, TALLOW, CHICKEN, POULTRY)
private val alcoholIngredients = setOf(ALCOHOL, MIRIN, COOKING_WINE)
private val pungentVegetables = setOf(ONION, GARLIC, SCALLION, CHIVE, WILD_CHIVE, ASAFOETIDA)

enum class DietCategory(
    val koreanName: String,
    val avoidedIngredients: Set<IngredientCode>,
) {
    VEGAN("비건", setOf(EGG, HONEY) + dairyIngredients + animalAdditives + animalFlesh),
    VEGETARIAN("베지테리언", animalAdditives + animalFlesh),
    LACTO_VEGETARIAN("락토 베지테리언", setOf(EGG) + animalAdditives + animalFlesh),
    OVO_VEGETARIAN("오보 베지테리언", dairyIngredients + animalAdditives + animalFlesh),
    PESCATARIAN("페스코테리언", animalAdditives + BROTH + landMeat),
    GLUTEN_FREE("글루텐 프리", setOf(WHEAT, BARLEY, RYE, OAT)),
    LACTOSE_FREE("유당 불내증", dairyIngredients),
    NO_ALCOHOL("무알코올", alcoholIngredients),
    MUSLIM("무슬림(할랄)", animalAdditives + setOf(BROTH, PORK, LARD, TALLOW) + alcoholIngredients),
    HINDU("힌두교", setOf(GELATIN, RENNET, BROTH, BEEF, TALLOW)),
    KOSHER("유대교(코셔)", animalAdditives + shellfishAndSeafood + setOf(BROTH, PORK, LARD)),
    BUDDHIST("불교(사찰식)", setOf(EGG) + animalAdditives + animalFlesh + pungentVegetables),
    JAIN(
        "자이나교",
        setOf(EGG, HONEY) + animalAdditives + animalFlesh +
            setOf(POTATO, CARROT, ONION, GARLIC, SCALLION, CHIVE, WILD_CHIVE),
    ),
    NUT_ALLERGY(
        "견과류 알레르기",
        setOf(WALNUT, PINE_NUT, ALMOND, CASHEW, PISTACHIO, HAZELNUT, MACADAMIA, PECAN, BRAZIL_NUT, CHESTNUT),
    ),
    SHELLFISH_ALLERGY("갑각류·조개 알레르기", shellfishAndSeafood),
}
