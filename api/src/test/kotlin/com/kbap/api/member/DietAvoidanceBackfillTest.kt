package com.kbap.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.IntegrationTest
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.ingredient.model.DietCategory
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class DietAvoidanceBackfillTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource

    private val mapper = jacksonObjectMapper()

    init {
        val migration = "db/migration/V2026.10.21.11.49.10__backfill_diet_avoidance_group_codes.sql"
        val kb842Codes = setOf("NUTS", "CRUSTACEAN", "MOLLUSK", "MEAT", "MEAT_BROTH", "LAMB")

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun runBackfill() {
            val sql = Thread.currentThread().contextClassLoader.getResource(migration)!!.readText()
            sql.lineSequence().filterNot { it.trim().startsWith("--") }.joinToString("\n").split(";")
                .map { it.trim() }.filter { it.isNotEmpty() }.forEach(::execute)
        }
        fun seed(id: Long, codes: List<String>, diets: List<String>, status: String = "ACTIVE") {
            FoodTestSeed.seedMemberAvoiding(dataSource, id, *codes.toTypedArray())
            execute("UPDATE member SET diet_categories = '${mapper.writeValueAsString(diets)}', status = '$status', updated_at = '2026-01-01 00:00:00' WHERE id = $id")
        }
        fun row(id: Long): Pair<List<String>, String> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT avoidance_substance_codes, updated_at FROM member WHERE id = $id").use { r ->
                    r.next(); mapper.readValue<List<String>>(r.getString(1)) to r.getString(2)
                }
            }
        }

        fun stored(diet: DietCategory): List<String> = diet.avoidedIngredients.map { it.name }.filterNot { it in kb842Codes }.sorted()
        fun required(diet: DietCategory, group: IngredientCode): Set<String> = (group.impliedCodes intersect diet.avoidedIngredients).map { it.name }.toSet()

        given("기존 식단 회원 회피 코드 백필(S-844)") {
            `when`("식단 하나를 고르고 그 식단 코드를 그대로 둔 회원마다 돌리면") {
                then("그 식단 매핑에 새로 들어간 KB-842 코드만 뒤에 붙는다(식단 매핑과 같은 규칙)") {
                    DietCategory.entries.forEachIndexed { i, diet -> seed(84500L + i, stored(diet), listOf(diet.name)) }
                    runBackfill()
                    DietCategory.entries.mapIndexedNotNull { i, diet ->
                        val expectedAdded = IngredientCode.entries.filter { it in diet.avoidedIngredients && it.name in kb842Codes }.map { it.name }.toSet()
                        val codes = row(84500L + i).first
                        "${diet.name}: ${codes}".takeIf { codes.take(stored(diet).size) != stored(diet) || codes.drop(stored(diet).size).toSet() != expectedAdded || codes.size != stored(diet).size + expectedAdded.size }
                    } shouldBe emptyList()
                }
            }
            `when`("식단은 유지하되 그 묶음의 기본 코드를 하나라도 직접 뺀 회원이면 — Codex P1") {
                then("그 묶음(과 양고기)은 가산하지 않는다 — 뺀 코드가 묶음 함의로 되살아나지 않게") {
                    val cases = listOf(DietCategory.VEGAN to IngredientCode.MEAT, DietCategory.PESCATARIAN to IngredientCode.MEAT, DietCategory.SHELLFISH_ALLERGY to IngredientCode.CRUSTACEAN, DietCategory.SHELLFISH_ALLERGY to IngredientCode.MOLLUSK)
                        .flatMap { (diet, group) -> required(diet, group).map { removed -> Triple(diet, group, removed) } }
                    cases.forEachIndexed { i, (diet, _, removed) -> seed(84600L + i, stored(diet) - removed, listOf(diet.name)) }
                    runBackfill()
                    cases.mapIndexedNotNull { i, (diet, group, removed) ->
                        val codes = row(84600L + i).first
                        val blocked = if (group == IngredientCode.MEAT) setOf("MEAT", "LAMB") else setOf(group.name)
                        "${diet.name}-$removed: $codes".takeIf { codes.any { it in blocked } }
                    } shouldBe emptyList()
                }
            }
            `when`("이미 일부 코드가 있거나, 식단이 둘이거나, 식단이 없거나, 삭제된 회원이면") {
                then("중복 없이 가산만 하고, 식단 없음·삭제 회원은 무변이며, 수정 시각은 그대로다") {
                    seed(84530L, stored(DietCategory.PESCATARIAN) + "MEAT", listOf("PESCATARIAN"))
                    seed(84531L, (stored(DietCategory.VEGAN) + stored(DietCategory.SHELLFISH_ALLERGY)).distinct(), listOf("VEGAN", "SHELLFISH_ALLERGY"))
                    seed(84532L, listOf("PORK"), listOf())
                    seed(84533L, listOf("EGG"), listOf("VEGAN"), status = "DELETED")
                    runBackfill()
                    row(84530L).first shouldBe stored(DietCategory.PESCATARIAN) + "MEAT" + "LAMB"
                    row(84531L).first.toSet() shouldBe (stored(DietCategory.VEGAN) + stored(DietCategory.SHELLFISH_ALLERGY)).toSet() + setOf("MEAT", "LAMB", "CRUSTACEAN", "MOLLUSK")
                    row(84532L).first shouldBe listOf("PORK")
                    row(84533L).first shouldBe listOf("EGG")
                    listOf(84530L, 84531L, 84532L, 84533L).map { row(it).second.take(19) }.toSet() shouldBe setOf("2026-01-01 00:00:00")
                }
            }
            `when`("두 번 돌리면") {
                then("두 번째는 아무것도 바꾸지 않는다") {
                    seed(84540L, (stored(DietCategory.VEGAN) + stored(DietCategory.SHELLFISH_ALLERGY)).distinct(), listOf("VEGAN", "SHELLFISH_ALLERGY"))
                    runBackfill()
                    val once = row(84540L)
                    runBackfill()
                    row(84540L) shouldBe once
                }
            }
        }
    }
}
