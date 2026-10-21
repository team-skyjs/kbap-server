package com.kbap.api.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec

@Tags("arch")
class IngredientLabelNoSafeVerdictTest : BehaviorSpec({
    val labelPackage = "com.kbap.common.domain.ingredient.label.."
    val imported =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.kbap.common.domain.ingredient.label")

    given("성분 표시 판독 패키지 — R-80 미검출을 SAFE 로 만들지 않는다") {
        `when`("패키지 클래스를 가져오면") {
            then("판독기가 실제로 존재한다(빈 패키지로 규칙이 공회전하지 않는다)") {
                classes().that().resideInAPackage(labelPackage).and().haveSimpleName("IngredientLabelReader")
                    .should().bePublic()
                    .check(imported)
            }
        }
        `when`("필드·메서드·enum 상수 이름을 검사하면") {
            then("safe·absent·free·notContain·allergenFree 같은 '없음' 판정 이름이 하나도 없다") {
                noMembers().that().areDeclaredInClassesThat().resideInAPackage(labelPackage)
                    .should().haveNameMatching("(?i).*(safe|absent|free|notcontain|without|clear).*")
                    .check(imported)
            }
        }
    }
})
