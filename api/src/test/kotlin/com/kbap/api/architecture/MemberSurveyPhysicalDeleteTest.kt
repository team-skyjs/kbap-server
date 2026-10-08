package com.kbap.api.architecture

import com.kbap.common.domain.member.MemberSurveyJpaRepository
import com.kbap.common.domain.member.model.MemberSurvey
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.data.jpa.repository.Query
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions

@Tags("arch")
class MemberSurveyPhysicalDeleteTest : BehaviorSpec({

    val production = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TEST_FIXTURES)
        .importPackages("com.kbap")

    fun softDeleteCallsIn(classes: JavaClasses): List<String> =
        classes.flatMap { it.codeUnits }.flatMap { it.methodCallsFromSelf }
            .filter { call ->
                call.name.startsWith("delete") &&
                    (call.targetOwner.isEquivalentTo(MemberSurvey::class.java) || call.targetOwner.isAssignableTo(MemberSurveyJpaRepository::class.java))
            }
            .map { "${it.originOwner.simpleName}.${it.origin.name} -> ${it.targetOwner.simpleName}.${it.name}" }
            .sorted()

    given("member_survey 는 물리 삭제 표다") {
        `when`("운영 코드를 전수 검사하면") {
            then("MemberSurvey.delete()·리포지토리 delete* 호출이 0건이다") {
                withClue(
                    "member_survey 는 member_id UNIQUE 라 소프트 삭제(status=DELETED)와 양립하지 않는다 — @SQLRestriction 이 숨긴 행이 " +
                        "unique 위반을 영원히 일으키고 surveyCompleted 도 false 로 남는다. 탈퇴 파기(KB-570)는 purgeByMemberId(네이티브 DELETE)만 쓴다.",
                ) {
                    softDeleteCallsIn(production) shouldBe emptyList()
                }
            }
            then("검출기는 소프트 삭제 호출을 실제로 잡는다(양성 대조군)") {
                softDeleteCallsIn(ClassFileImporter().importClasses(MemberSurveySoftDeleteFixture::class.java)) shouldBe listOf(
                    "MemberSurveySoftDeleteFixture.entityDelete -> MemberSurvey.delete",
                    "MemberSurveySoftDeleteFixture.repositoryDelete -> MemberSurveyJpaRepository.delete",
                )
            }
        }
        `when`("파기 메서드를 보면") {
            then("purgeByMemberId 는 네이티브 DELETE 다") {
                val query = MemberSurveyJpaRepository::class.functions.single { it.name == "purgeByMemberId" }.findAnnotation<Query>()!!
                query.nativeQuery shouldBe true
                query.value.trim().uppercase() shouldStartWith "DELETE FROM MEMBER_SURVEY WHERE MEMBER_ID"
            }
        }
    }
})

class MemberSurveySoftDeleteFixture(private val repository: MemberSurveyJpaRepository) {
    fun entityDelete(survey: MemberSurvey) = survey.delete()

    fun repositoryDelete(survey: MemberSurvey) = repository.delete(survey)
}
