package com.kbap.api.architecture

import com.kbap.api.admin.AdminFoodContentDraftService
import com.kbap.api.admin.AdminFoodContentIngestService
import com.kbap.api.admin.AdminFoodIngredientBackfillService
import com.kbap.api.admin.AdminFoodService
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

@Tags("arch")
class FoodIngredientReplaceCallersTest : BehaviorSpec({

    val imported = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TEST_FIXTURES)
        .importPackages("com.kbap")

    val replaceCalls = imported.get(FoodIngredientJdbcRepository::class.java).methods
        .single { it.name == "replace" }
        .callsOfSelf

    fun firstRepositoryCallOf(codeUnit: JavaCodeUnit): String =
        codeUnit.callsFromSelf
            .filter { it.targetOwner.simpleName.endsWith("Repository") }
            .minBy { it.lineNumber }
            .name

    fun method(owner: Class<*>, name: String): JavaCodeUnit = imported.get(owner).codeUnits.single { it.name == name }

    given("재료 관계 교체(replace)를 부르는 자리") {
        `when`("운영 코드를 전수 검사하면") {
            then("수집 결과 반영·어드민 음식 수정·재료 백필 셋뿐이다 — 새 호출자는 아래 전제를 확인하고 이 목록에 더한다") {
                withClue(
                    "replace 는 그 음식의 재료 행을 잠금 없이 읽어 읽힌 행만 지운다. 새 호출자가 음식 행을 잠그기 전에 다른 조회를 하면 " +
                        "옛 목록을 읽어 재료 행이 조용히 남는다. 범위 삭제로 되돌리면 KB-682 교착이 돌아온다.",
                ) {
                    replaceCalls.map { "${it.originOwner.simpleName.substringBefore('$')}.${it.origin.name.substringBefore('$')}" }.sorted() shouldBe listOf(
                        "AdminFoodContentIngestService.applyContent",
                        "AdminFoodIngredientBackfillService.backfill",
                        "AdminFoodService.updateFood",
                    )
                }
            }
        }

        `when`("각 호출 경로의 트랜잭션이 시작하면") {
            then("첫 리포지토리 호출이 음식 행 잠금(…ForUpdate)이다 — 잠금 전 조회가 있으면 replace 가 옛 목록을 읽는다") {
                val lockFirst = listOf(
                    method(AdminFoodContentIngestService::class.java, "lockFoodAndCompleteOutbox"),
                    method(AdminFoodContentDraftService::class.java, "reviewDraft"),
                    method(AdminFoodService::class.java, "updateFood"),
                    replaceCalls.single { it.originOwner.name.startsWith(AdminFoodIngredientBackfillService::class.java.name) }.origin,
                )

                lockFirst.forEach { codeUnit ->
                    withClue("${codeUnit.fullName} 의 첫 리포지토리 호출") { firstRepositoryCallOf(codeUnit) shouldEndWith "ForUpdate" }
                }
            }

            then("수집 결과 반영은 음식 행 잠금으로 시작한다") {
                method(AdminFoodContentIngestService::class.java, "ingestContent").callsFromSelf
                    .filter { it.targetOwner.simpleName.endsWith("Repository") || it.targetOwner.isEquivalentTo(AdminFoodContentIngestService::class.java) }
                    .minBy { it.lineNumber }
                    .name shouldBe "lockFoodAndCompleteOutbox"
            }
        }
    }
})
